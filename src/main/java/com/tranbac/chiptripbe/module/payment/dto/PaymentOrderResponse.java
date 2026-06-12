package com.tranbac.chiptripbe.module.payment.dto;

import com.tranbac.chiptripbe.module.payment.entity.OrderStatus;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record PaymentOrderResponse(
        Long orderId,
        String orderCode,
        String planCode,
        Long amountVnd,
        Integer credits,
        OrderStatus status,
        String qrUrl,
        String bankName,
        String accountNumber,
        String accountHolder,
        String transferContent,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime paidAt
) {}
