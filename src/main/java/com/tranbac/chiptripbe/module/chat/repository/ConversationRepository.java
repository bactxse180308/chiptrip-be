package com.tranbac.chiptripbe.module.chat.repository;

import com.tranbac.chiptripbe.module.chat.entity.Conversation;
import com.tranbac.chiptripbe.module.chat.enums.ConversationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /** Hội thoại OPEN gần nhất của user (theo design hiện tại có tối đa 1). */
    Optional<Conversation> findFirstByUserIdAndStatusOrderByCreatedAtDesc(Long userId, ConversationStatus status);

    Optional<Conversation> findByIdAndUserId(Long id, Long userId);

    /**
     * Danh sách hội thoại cho admin, kèm sort lastMessageAt desc (null cuối).
     * Dùng @Query để xử lý NULLS LAST đồng nhất giữa các DB dialect.
     */
    @Query("SELECT c FROM Conversation c JOIN FETCH c.user u WHERE c.status = :status " +
            "ORDER BY CASE WHEN c.lastMessageAt IS NULL THEN 1 ELSE 0 END, c.lastMessageAt DESC, c.id DESC")
    Page<Conversation> findActiveForAdmin(@Param("status") ConversationStatus status, Pageable pageable);
}
