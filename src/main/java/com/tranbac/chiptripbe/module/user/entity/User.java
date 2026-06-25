package com.tranbac.chiptripbe.module.user.entity;

import com.tranbac.chiptripbe.common.entity.BaseAuditEntity;
import com.tranbac.chiptripbe.common.enums.OAuthProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_users_oauth_provider_id", columnNames = {"oauth_provider", "oauth_provider_id"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseAuditEntity {

    @Column(name = "email", nullable = false, length = 150)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Nationalized
    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "ai_credits", nullable = false)
    @Builder.Default
    private Integer aiCredits = 3;

    @Column(name = "ai_credit_units")
    private Integer aiCreditUnits;

    /** Lượt miễn phí hôm nay (0 hoặc 1, KHÔNG cộng dồn). Chỉ dùng cho POST /trips/generate. */
    @Column(name = "trial_credit_balance", columnDefinition = "INT NOT NULL DEFAULT 0")
    @Builder.Default
    private int trialCreditBalance = 0;

    /** Ngày cấp trial gần nhất (theo Asia/Ho_Chi_Minh). */
    @Column(name = "trial_credit_date")
    private LocalDate trialCreditDate;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private Boolean emailVerified = false;

    @Column(name = "last_login_at", columnDefinition = "DATETIME2")
    private LocalDateTime lastLoginAt;

    @ManyToOne(fetch = FetchType.EAGER, optional = false,cascade = CascadeType.ALL)
    @JoinColumn(name = "role_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_users_role"))
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", length = 20)
    private OAuthProvider oauthProvider;

    @Column(name = "oauth_provider_id", length = 255)
    private String oauthProviderId;

    @Nationalized
    @Column(name = "preferences", length = 500)
    private String preferences;

    public boolean hasRole(String roleName) {
        return role != null && role.getName().equals(roleName);
    }

    public boolean isOAuthUser() {
        return oauthProvider != null;
    }

    /**
     * Premium là trạng thái SUY RA từ paid credit còn lại — KHÔNG dùng ROLE_PREMIUM.
     * isPremium = paidCreditBalance > 0 (ở đây paid = aiCreditUnits, 100 units = 1.00 credit).
     * Hết paid → tự động về Normal, không cron, không cập nhật cờ.
     */
    @Transient
    public boolean isPremium() {
        return effectiveAiCreditUnits() > 0;
    }

    @PrePersist
    void initCreditUnits() {
        if (aiCreditUnits == null) {
            aiCreditUnits = safeCredits() * 100;
        }
    }

    public int effectiveAiCreditUnits() {
        return aiCreditUnits != null ? aiCreditUnits : safeCredits() * 100;
    }

    public BigDecimal aiCreditBalance() {
        return BigDecimal.valueOf(effectiveAiCreditUnits())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN)
                .stripTrailingZeros();
    }

    public void setWholeAiCredits(int credits) {
        int safe = Math.max(0, credits);
        this.aiCredits = safe;
        this.aiCreditUnits = safe * 100;
    }

    public void addWholeAiCredits(int amount) {
        setWholeAiCredits(safeCredits() + Math.max(0, amount));
    }

    private int safeCredits() {
        return aiCredits != null ? Math.max(0, aiCredits) : 0;
    }
}
