package com.tranbac.chiptripbe.module.chat.repository;

import com.tranbac.chiptripbe.module.chat.entity.ChatMessage;
import com.tranbac.chiptripbe.module.chat.enums.SenderRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** Lần đầu mở khung chat — lấy N tin mới nhất. */
    Page<ChatMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);

    /** Cursor: load tin cũ hơn 1 mốc id (id < beforeId). */
    Page<ChatMessage> findByConversationIdAndIdLessThanOrderByIdDesc(Long conversationId, Long beforeId, Pageable pageable);

    /**
     * Đếm tin của 1 phía (USER hoặc ADMIN) có id > lastReadByMsgId.
     * Dùng cho unreadCount của phía đối diện (admin xem unread tin user, user xem unread tin admin).
     */
    long countByConversationIdAndSenderRoleAndIdGreaterThan(Long conversationId, SenderRole senderRole, Long afterMsgId);

    long countByConversationIdAndSenderRole(Long conversationId, SenderRole senderRole);

    long countByConversationId(Long conversationId);
}
