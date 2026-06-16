package com.tranbac.chiptripbe.module.payment.repository;

import com.tranbac.chiptripbe.module.payment.entity.OrderStatus;
import com.tranbac.chiptripbe.module.payment.entity.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByOrderCode(String orderCode);

    Optional<PaymentOrder> findByIdAndUserId(Long id, Long userId);

    /** Toàn bộ đơn của 1 user, mới nhất trước — dùng cho trang chi tiết user (admin). */
    List<PaymentOrder> findByUserIdOrderByCreatedAtDesc(Long userId);

    boolean existsByOrderCode(String orderCode);

    /**
     * Đánh dấu PAID atomic, chỉ khi đang PENDING (chống đối soát/cộng credit 2 lần khi
     * 2 giao dịch cùng trỏ về 1 order). Trả 1 nếu chuyển trạng thái thành công, 0 nếu đã PAID.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentOrder o SET o.status = :paid, o.paidAt = :paidAt, o.sepayTransactionId = :txId " +
           "WHERE o.id = :orderId AND o.status = :pending")
    int markPaidIfPending(@Param("orderId") Long orderId,
                          @Param("paidAt") LocalDateTime paidAt,
                          @Param("txId") Long txId,
                          @Param("paid") OrderStatus paid,
                          @Param("pending") OrderStatus pending);
}
