package com.tranbac.chiptripbe.module.chat.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.chat.dto.response.AdminConversationDto;
import com.tranbac.chiptripbe.module.chat.dto.response.ConversationDto;
import com.tranbac.chiptripbe.module.chat.dto.response.MessageDto;
import com.tranbac.chiptripbe.module.chat.entity.ChatMessage;
import com.tranbac.chiptripbe.module.chat.entity.Conversation;
import com.tranbac.chiptripbe.module.chat.enums.ConversationStatus;
import com.tranbac.chiptripbe.module.chat.enums.MessageType;
import com.tranbac.chiptripbe.module.chat.enums.SenderRole;
import com.tranbac.chiptripbe.module.chat.repository.ChatMessageRepository;
import com.tranbac.chiptripbe.module.chat.repository.ConversationRepository;
import com.tranbac.chiptripbe.module.chat.config.ChatProperties;
import com.tranbac.chiptripbe.module.chat.service.ChatService;
import com.tranbac.chiptripbe.module.chat.service.R2StorageService;
import com.tranbac.chiptripbe.module.notification.event.NewSupportMessageEvent;
import com.tranbac.chiptripbe.module.notification.event.SupportReplyEvent;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Chat hỗ trợ giữa user và admin.
 *
 * Quy ước:
 * - DB là source of truth: lưu DB trước rồi mới fanout WS.
 * - Lỗi đẩy WS chỉ log warn, KHÔNG fail transaction (try/catch hẹp quanh send).
 * - "1 user = 1 hội thoại OPEN" enforce ở method getOrCreateActiveConversation
 *   (KHÔNG đặt unique trên DB) để có thể mở rộng ticket sau này.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ChatServiceImpl implements ChatService {

    private static final String USER_QUEUE = "/queue/messages";
    private static final String ADMIN_TOPIC = "/topic/support";
    private static final int PREVIEW_MAX_LEN = 100;

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final R2StorageService r2StorageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatProperties chatProperties;

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    // ============== USER ==============

    @Override
    @Transactional
    public ConversationDto getOrCreateForUser(Long userId) {
        Conversation c = getOrCreateActiveConversation(userId);
        long unread = countUnreadForUser(c);
        return ConversationDto.from(c, unread);
    }

    @Override
    @Transactional
    public MessageDto sendTextAsUser(Long userId, String content) {
        Conversation c = getOrCreateActiveConversation(userId);
        boolean isFirstMessage = chatMessageRepository.countByConversationId(c.getId()) == 0;
        User sender = loadUser(userId);
        ChatMessage saved = saveMessage(c, sender, SenderRole.USER, MessageType.TEXT, content, null, null);
        fanoutUserSent(c, saved);
        publishNewSupportMessageEvent(c, sender, content);
        if (isFirstMessage) maybeAutoReply(c);
        return MessageDto.from(saved);
    }

    @Override
    @Transactional
    public MessageDto sendImageAsUser(Long userId, MultipartFile file) {
        Conversation c = getOrCreateActiveConversation(userId);
        boolean isFirstMessage = chatMessageRepository.countByConversationId(c.getId()) == 0;
        // Upload R2 trước rồi mới lưu DB — nếu DB lỗi ảnh thành "rác" có thể cleanup;
        // ngược lại nếu lưu DB trước rồi upload lỗi sẽ có row trỏ tới ảnh không tồn tại.
        R2StorageService.UploadResult uploaded = r2StorageService.uploadChatImage(file, c.getId());
        User sender = loadUser(userId);
        ChatMessage saved = saveMessage(c, sender, SenderRole.USER, MessageType.IMAGE, null,
                uploaded.imageUrl(), uploaded.imageKey());
        fanoutUserSent(c, saved);
        publishNewSupportMessageEvent(c, sender, "[Ảnh]");
        if (isFirstMessage) maybeAutoReply(c);
        return MessageDto.from(saved);
    }

    @Override
    public List<MessageDto> historyForUser(Long userId, Long beforeId, int size) {
        Conversation c = getActiveConversationOrThrow(userId);
        return loadHistory(c.getId(), beforeId, size);
    }

    @Override
    @Transactional
    public void markReadByUser(Long userId) {
        Conversation c = getActiveConversationOrThrow(userId);
        // Lấy id tin admin lớn nhất hiện có trong hội thoại để mark
        Pageable top1 = PageRequest.of(0, 1);
        Page<ChatMessage> latest = chatMessageRepository.findByConversationIdOrderByIdDesc(c.getId(), top1);
        if (!latest.isEmpty()) {
            c.setLastReadByUserMsgId(latest.getContent().get(0).getId());
            conversationRepository.save(c);
        }
    }

    // ============== ADMIN ==============

    @Override
    public Page<AdminConversationDto> listConversationsForAdmin(String statusOrNull, Pageable pageable) {
        ConversationStatus status = parseStatus(statusOrNull);
        Page<Conversation> page = conversationRepository.findActiveForAdmin(status, pageable);
        return page.map(c -> {
            long unread = countUnreadForAdmin(c);
            String preview = loadPreview(c.getId());
            return AdminConversationDto.from(c, preview, unread);
        });
    }

    @Override
    public List<MessageDto> historyForAdmin(Long conversationId, Long beforeId, int size) {
        // Đảm bảo hội thoại tồn tại; admin có quyền xem mọi hội thoại nên chỉ check tồn tại.
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy hội thoại"));
        return loadHistory(conversationId, beforeId, size);
    }

    @Override
    @Transactional
    public MessageDto sendTextAsAdmin(Long adminId, Long conversationId, String content) {
        Conversation c = conversationRepository.findById(conversationId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy hội thoại"));
        User admin = loadUser(adminId);
        ChatMessage saved = saveMessage(c, admin, SenderRole.ADMIN, MessageType.TEXT, content, null, null);
        fanoutAdminSent(c, saved);
        publishSupportReplyEvent(c, content);
        return MessageDto.from(saved);
    }

    @Override
    @Transactional
    public MessageDto sendImageAsAdmin(Long adminId, Long conversationId, MultipartFile file) {
        Conversation c = conversationRepository.findById(conversationId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy hội thoại"));
        R2StorageService.UploadResult uploaded = r2StorageService.uploadChatImage(file, c.getId());
        User admin = loadUser(adminId);
        ChatMessage saved = saveMessage(c, admin, SenderRole.ADMIN, MessageType.IMAGE, null,
                uploaded.imageUrl(), uploaded.imageKey());
        fanoutAdminSent(c, saved);
        publishSupportReplyEvent(c, "[Ảnh]");
        return MessageDto.from(saved);
    }

    @Override
    @Transactional
    public void markReadByAdmin(Long conversationId) {
        Conversation c = conversationRepository.findById(conversationId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy hội thoại"));
        Pageable top1 = PageRequest.of(0, 1);
        Page<ChatMessage> latest = chatMessageRepository.findByConversationIdOrderByIdDesc(c.getId(), top1);
        if (!latest.isEmpty()) {
            c.setLastReadByAdminMsgId(latest.getContent().get(0).getId());
            conversationRepository.save(c);
        }
    }

    @Override
    @Transactional
    public void closeConversation(Long conversationId) {
        Conversation c = conversationRepository.findById(conversationId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy hội thoại"));
        c.setStatus(ConversationStatus.CLOSED);
        conversationRepository.save(c);
        log.info("Conversation id={} closed by admin", conversationId);
    }

    // ============== Helpers ==============

    private Conversation getOrCreateActiveConversation(Long userId) {
        return conversationRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, ConversationStatus.OPEN)
                .orElseGet(() -> {
                    User user = loadUser(userId);
                    Conversation c = Conversation.builder()
                            .user(user)
                            .status(ConversationStatus.OPEN)
                            .build();
                    return conversationRepository.save(c);
                });
    }

    private Conversation getActiveConversationOrThrow(Long userId) {
        return conversationRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, ConversationStatus.OPEN)
                .orElseThrow(() -> AppException.notFound("Bạn chưa có hội thoại hỗ trợ"));
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));
    }

    private ChatMessage saveMessage(Conversation c, User sender, SenderRole role, MessageType type,
                                    String content, String imageUrl, String imageKey) {
        ChatMessage m = ChatMessage.builder()
                .conversation(c)
                .sender(sender)
                .senderRole(role)
                .messageType(type)
                .content(content)
                .imageUrl(imageUrl)
                .imageKey(imageKey)
                .build();
        m = chatMessageRepository.save(m);
        c.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(c);
        return m;
    }

    private List<MessageDto> loadHistory(Long conversationId, Long beforeId, int size) {
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(0, safeSize);
        Page<ChatMessage> page = (beforeId == null)
                ? chatMessageRepository.findByConversationIdOrderByIdDesc(conversationId, pageable)
                : chatMessageRepository.findByConversationIdAndIdLessThanOrderByIdDesc(conversationId, beforeId, pageable);
        return page.getContent().stream().map(MessageDto::from).toList();
    }

    private long countUnreadForUser(Conversation c) {
        Long after = c.getLastReadByUserMsgId();
        if (after == null) {
            return chatMessageRepository.countByConversationIdAndSenderRole(c.getId(), SenderRole.ADMIN);
        }
        return chatMessageRepository.countByConversationIdAndSenderRoleAndIdGreaterThan(
                c.getId(), SenderRole.ADMIN, after);
    }

    private long countUnreadForAdmin(Conversation c) {
        Long after = c.getLastReadByAdminMsgId();
        if (after == null) {
            return chatMessageRepository.countByConversationIdAndSenderRole(c.getId(), SenderRole.USER);
        }
        return chatMessageRepository.countByConversationIdAndSenderRoleAndIdGreaterThan(
                c.getId(), SenderRole.USER, after);
    }

    private String loadPreview(Long conversationId) {
        Page<ChatMessage> latest = chatMessageRepository.findByConversationIdOrderByIdDesc(
                conversationId, PageRequest.of(0, 1));
        if (latest.isEmpty()) return null;
        ChatMessage last = latest.getContent().get(0);
        if (last.getMessageType() == MessageType.IMAGE) return "[Ảnh]";
        String c = last.getContent();
        if (c == null) return null;
        return c.length() > PREVIEW_MAX_LEN ? c.substring(0, PREVIEW_MAX_LEN) + "…" : c;
    }

    private ConversationStatus parseStatus(String s) {
        if (s == null || s.isBlank()) return ConversationStatus.OPEN;
        try {
            return ConversationStatus.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw AppException.badRequest("status không hợp lệ");
        }
    }

    // ============== WebSocket fanout ==============

    private void fanoutUserSent(Conversation c, ChatMessage m) {
        MessageDto dto = MessageDto.from(m);
        // Bắn về chính user (đa thiết bị) + admin pool
        sendToUser(c.getUser().getId(), dto);
        sendToAdminTopic(dto);
    }

    private void fanoutAdminSent(Conversation c, ChatMessage m) {
        MessageDto dto = MessageDto.from(m);
        sendToUser(c.getUser().getId(), dto);
        sendToAdminTopic(dto);
    }

    private void sendToUser(Long userId, MessageDto dto) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), USER_QUEUE, dto);
        } catch (MessagingException ex) {
            log.warn("Failed to push chat message {} to userId={}: {}",
                    dto.id(), userId, ex.getClass().getSimpleName());
        }
    }

    private void sendToAdminTopic(MessageDto dto) {
        try {
            messagingTemplate.convertAndSend(ADMIN_TOPIC, dto);
        } catch (MessagingException ex) {
            log.warn("Failed to push chat message {} to admin topic: {}",
                    dto.id(), ex.getClass().getSimpleName());
        }
    }

    /**
     * Publish event để NotificationEventListener tạo SUPPORT_REPLY + push WS sau khi
     * chat transaction commit thành công (AFTER_COMMIT). Đảm bảo notification fail
     * không kéo chat rollback (đã từng dính lỗi CHECK constraint).
     */
    private void publishSupportReplyEvent(Conversation c, String contentForPreview) {
        eventPublisher.publishEvent(new SupportReplyEvent(
                c.getUser().getId(), c.getId(), truncatePreview(contentForPreview)));
    }

    /** Publish event để admin nhận NEW_SUPPORT_MESSAGE notification (dedup ở listener). */
    private void publishNewSupportMessageEvent(Conversation c, User sender, String contentForPreview) {
        eventPublisher.publishEvent(new NewSupportMessageEvent(
                c.getId(), sender.getFullName(), truncatePreview(contentForPreview)));
    }

    /**
     * Tự động gửi 1 tin từ phía ADMIN khi conversation vừa có tin USER đầu tiên.
     * Sender là admin active đầu tiên tìm được. KHÔNG publish SupportReplyEvent
     * (auto-reply không tính là admin "đã phản hồi") để tránh đẩy noti gây hiểu nhầm.
     */
    private void maybeAutoReply(Conversation c) {
        String text = chatProperties.getAutoReplyMessage();
        if (text == null || text.isBlank()) return;

        User systemAdmin = userRepository.findAllByRole_NameAndIsActiveTrue(ROLE_ADMIN).stream()
                .findFirst().orElse(null);
        if (systemAdmin == null) {
            log.warn("Auto-reply skipped: no active admin in system for conv={}", c.getId());
            return;
        }
        ChatMessage auto = saveMessage(c, systemAdmin, SenderRole.ADMIN, MessageType.TEXT, text, null, null);
        fanoutAdminSent(c, auto);
        log.info("Auto-reply sent on conv={} as adminId={}", c.getId(), systemAdmin.getId());
    }

    private String truncatePreview(String content) {
        String preview = content == null ? "" : content;
        if (preview.length() > PREVIEW_MAX_LEN) {
            preview = preview.substring(0, PREVIEW_MAX_LEN) + "…";
        }
        return preview;
    }
}
