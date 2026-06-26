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

    long countByStatus(OrderStatus status);

    @Query("SELECT COALESCE(SUM(o.amountVnd), 0) " +
           "FROM PaymentOrder o " +
           "WHERE o.status = :status AND o.paidAt >= :from AND o.paidAt <= :to")
    Long sumAmountVndByStatusAndPaidAtBetween(@Param("status") OrderStatus status,
                                              @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);

    /** Số đơn + doanh thu (VNĐ) theo từng ngày, lọc theo trạng thái và khoảng paidAt. */
    @Query("SELECT YEAR(o.paidAt), MONTH(o.paidAt), DAY(o.paidAt), COUNT(o), COALESCE(SUM(o.amountVnd), 0) " +
           "FROM PaymentOrder o WHERE o.status = :status AND o.paidAt >= :from AND o.paidAt <= :to " +
           "GROUP BY YEAR(o.paidAt), MONTH(o.paidAt), DAY(o.paidAt) " +
           "ORDER BY YEAR(o.paidAt), MONTH(o.paidAt), DAY(o.paidAt)")
    List<Object[]> aggregateRevenueByDay(@Param("status") OrderStatus status,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

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
