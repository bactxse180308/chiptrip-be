package com.tranbac.chiptripbe.module.chat.service;

import com.tranbac.chiptripbe.module.chat.dto.response.AdminConversationDto;
import com.tranbac.chiptripbe.module.chat.dto.response.ConversationDto;
import com.tranbac.chiptripbe.module.chat.dto.response.MessageDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ChatService {

    // ----- User side -----

    /** Trả conversation OPEN của user, tự tạo nếu chưa có. Bao gồm unreadCount tin admin. */
    ConversationDto getOrCreateForUser(Long userId);

    MessageDto sendTextAsUser(Long userId, String content);

    MessageDto sendImageAsUser(Long userId, MultipartFile file);

    /** Lịch sử cursor: trả mới→cũ; nếu beforeId null thì lấy mới nhất. */
    List<MessageDto> historyForUser(Long userId, Long beforeId, int size);

    void markReadByUser(Long userId);

    // ----- Admin side -----

    Page<AdminConversationDto> listConversationsForAdmin(String statusOrNull, Pageable pageable);

    List<MessageDto> historyForAdmin(Long conversationId, Long beforeId, int size);

    MessageDto sendTextAsAdmin(Long adminId, Long conversationId, String content);

    MessageDto sendImageAsAdmin(Long adminId, Long conversationId, MultipartFile file);

    void markReadByAdmin(Long conversationId);

    void closeConversation(Long conversationId);
}
