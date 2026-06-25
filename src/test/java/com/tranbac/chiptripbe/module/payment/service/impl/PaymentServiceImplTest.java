package com.tranbac.chiptripbe.module.payment.service.impl;

import com.tranbac.chiptripbe.module.payment.config.SepayProperties;
import com.tranbac.chiptripbe.module.payment.dto.SepayWebhookPayload;
import com.tranbac.chiptripbe.module.payment.entity.OrderStatus;
import com.tranbac.chiptripbe.module.payment.entity.PaymentOrder;
import com.tranbac.chiptripbe.module.payment.repository.PaymentOrderRepository;
import com.tranbac.chiptripbe.module.payment.repository.PaymentTransactionRepository;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Webhook SePay sau khi BỎ nâng ROLE_PREMIUM: vẫn cộng paid credit đúng 1 lần & idempotent.
 * CREDIT_PREMIUM_SPEC.md Mục 5.8.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceImplTest {

    private static final Long TX_ID = 99L;
    private static final Long ORDER_ID = 50L;
    private static final Long USER_ID = 5L;
    private static final String ORDER_CODE = "CHIP1234ABCD";

    @Mock private PaymentTransactionRepository paymentRepo;
    @Mock private PaymentOrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private SepayProperties sepayProperties;

    @InjectMocks private PaymentServiceImpl service;

    @Test
    void processWebhook_validPayment_addsPaidCreditOnce() {
        stubPrefix();
        when(paymentRepo.existsBySepayTransactionId(TX_ID)).thenReturn(false);
        when(orderRepository.findByOrderCode(ORDER_CODE)).thenReturn(Optional.of(pendingOrder()));
        when(orderRepository.markPaidIfPending(eq(ORDER_ID), any(LocalDateTime.class), eq(TX_ID),
                eq(OrderStatus.PAID), eq(OrderStatus.PENDING))).thenReturn(1);

        service.processWebhook(payload());

        verify(userRepository).addCredits(USER_ID, 1);   // +1 credit = +100 units (gói PREMIUM)
        verify(paymentRepo).save(any());
    }

    @Test
    void processWebhook_concurrentlySettled_doesNotAddCredit() {
        stubPrefix();
        when(paymentRepo.existsBySepayTransactionId(TX_ID)).thenReturn(false);
        when(orderRepository.findByOrderCode(ORDER_CODE)).thenReturn(Optional.of(pendingOrder()));
        when(orderRepository.markPaidIfPending(eq(ORDER_ID), any(LocalDateTime.class), eq(TX_ID),
                eq(OrderStatus.PAID), eq(OrderStatus.PENDING))).thenReturn(0);   // thua cuộc đua

        service.processWebhook(payload());

        verify(userRepository, never()).addCredits(anyLong(), anyInt());
    }

    @Test
    void processWebhook_duplicateTransaction_skipsBeforeOrderLookup() {
        when(paymentRepo.existsBySepayTransactionId(TX_ID)).thenReturn(true);

        service.processWebhook(payload());

        verify(orderRepository, never()).findByOrderCode(any());
        verify(userRepository, never()).addCredits(anyLong(), anyInt());
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void stubPrefix() {
        when(sepayProperties.getNormalizedOrderCodePrefix()).thenReturn("CHIP");
    }

    private PaymentOrder pendingOrder() {
        PaymentOrder order = PaymentOrder.builder()
                .orderCode(ORDER_CODE)
                .userId(USER_ID)
                .planCode("PREMIUM")
                .amountVnd(10_000L)
                .credits(1)
                .status(OrderStatus.PENDING)
                .build();
        order.setId(ORDER_ID);
        return order;
    }

    private SepayWebhookPayload payload() {
        SepayWebhookPayload payload = new SepayWebhookPayload();
        payload.setId(TX_ID);
        payload.setTransferType("in");
        payload.setContent(ORDER_CODE);
        payload.setTransferAmount(10_000L);
        return payload;
    }
}
