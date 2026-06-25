package com.tranbac.chiptripbe.module.payment.entity;

import com.tranbac.chiptripbe.common.entity.BaseEntity;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_transactions", indexes = {
        @Index(name = "idx_payment_user", columnList = "user_id"),
        @Index(name = "idx_payment_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransaction extends BaseEntity {

    @Column(name = "sepay_transaction_id", nullable = false, unique = true)
    private Long sepayTransactionId;

    @Column(name = "user_id")
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_payment_transactions_user"))
    private User user;

    @Column(nullable = false, length = 100)
    private String gateway;

    @Column(name = "transaction_date")
    private LocalDateTime transactionDate;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(length = 100)
    private String code;

    @Column(length = 500)
    private String content;

    @Column(name = "transfer_type", length = 10)
    private String transferType;

    @Column(name = "transfer_amount", nullable = false)
    private Long transferAmount;

    @Column(name = "reference_code", length = 100)
    private String referenceCode;

    @Column(name = "credits_granted", nullable = false)
    private Integer creditsGranted;

    @Column(name = "plan_code", length = 30)
    private String planCode;

    /** Mã đơn hàng đối soát được từ nội dung CK (null = không khớp đơn nào). */
    @Column(name = "order_code", length = 30)
    private String orderCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
