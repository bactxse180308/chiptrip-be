package com.tranbac.chiptripbe.module.auth.entity;

import com.tranbac.chiptripbe.common.entity.BaseEntity;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_verification_tokens",
        indexes = @Index(name = "ix_email_verification_tokens_token", columnList = "token", unique = true))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerificationToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_email_verification_tokens_user"))
    private User user;

    @Column(name = "token", nullable = false, length = 36)
    private String token;

    @Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME2")
    private LocalDateTime expiresAt;

    @Column(name = "used", nullable = false)
    @Builder.Default
    private Boolean used = false;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME2")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
