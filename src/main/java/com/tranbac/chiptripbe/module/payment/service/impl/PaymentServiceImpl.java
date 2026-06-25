package com.tranbac.chiptripbe.module.payment.service.impl;

import com.tranbac.chiptripbe.module.payment.config.SepayProperties;
import com.tranbac.chiptripbe.module.payment.dto.SepayWebhookPayload;
import com.tranbac.chiptripbe.module.payment.entity.OrderStatus;
import com.tranbac.chiptripbe.module.payment.entity.PaymentOrder;
import com.tranbac.chiptripbe.module.payment.entity.PaymentTransaction;
import com.tranbac.chiptripbe.module.payment.repository.PaymentOrderRepository;
import com.tranbac.chiptripbe.module.payment.repository.PaymentTransactionRepository;
import com.tranbac.chiptripbe.module.payment.service.PaymentService;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
class PaymentServiceImpl implements PaymentService {

    private final PaymentTransactionRepository paymentRepo;
    private final PaymentOrderRepository orderRepository;
    private final UserRepository userRepository;
    private final SepayProperties sepayProperties;

    // Mã đơn hàng = "CHIP" + 8 hex (sinh ở OrderServiceImpl). Tìm trong nội dung CK.
    private static final DateTimeFormatter SEPAY_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional
    public void processWebhook(SepayWebhookPayload payload) {
        if (payload.getId() == null || payload.getId() <= 0) {
            log.info("SePay test/invalid webhook ignored: id={}, content='{}', amount={}",
                    payload.getId(), payload.getContent(), payload.getTransferAmount());
            return;
        }

        if (!"in".equalsIgnoreCase(payload.getTransferType())) {
            log.debug("Ignoring non-inbound transaction id={}", payload.getId());
            return;
        }

        // Idempotency: 1 giao dịch SePay chỉ xử lý đúng 1 lần (unique constraint là chốt chặn cuối).
        if (paymentRepo.existsBySepayTransactionId(payload.getId())) {
            log.info("Duplicate webhook ignored: sepayTransactionId={}", payload.getId());
            return;
        }

        String orderCode = firstOrderCode(
                payload.getContent(),
                payload.getCode(),
                payload.getDescription(),
                payload.getReferenceCode());

        PaymentOrder order = orderCode == null
                ? null
                : orderRepository.findByOrderCode(orderCode).orElse(null);

        int creditsGranted = 0;
        Long matchedUserId = null;
        String matchedPlan = null;
        String matchedCode = null;

        if (order == null) {
            log.warn("No matching order for webhook content='{}', code='{}', sepayId={}",
                    payload.getContent(), payload.getCode(), payload.getId());
        } else if (order.getStatus() != OrderStatus.PENDING) {
            log.info("Order {} already settled (status={}), sepayId={}",
                    order.getOrderCode(), order.getStatus(), payload.getId());
            matchedCode = order.getOrderCode();
        } else if (payload.getTransferAmount() == null || payload.getTransferAmount() < order.getAmountVnd()) {
            log.warn("Amount {} < required {} for order {}, sepayId={}",
                    payload.getTransferAmount(), order.getAmountVnd(), order.getOrderCode(), payload.getId());
            matchedCode = order.getOrderCode();
        } else {
            // Conditional update PENDING→PAID: chỉ 1 giao dịch thắng cuộc đua, không cộng credit 2 lần.
            int updated = orderRepository.markPaidIfPending(
                    order.getId(), LocalDateTime.now(), payload.getId(),
                    OrderStatus.PAID, OrderStatus.PENDING);
            if (updated == 1) {
                // Cộng paid credit → user TỰ thành Premium vì paidCreditBalance > 0.
                // KHÔNG nâng ROLE_PREMIUM: role slot dành cho RBAC (USER/ADMIN); premium là
                // trạng thái suy ra từ paid, tránh desync khi user xài hết paid. Spec Mục 5.8.
                userRepository.addCredits(order.getUserId(), order.getCredits());
                creditsGranted = order.getCredits();
                matchedUserId = order.getUserId();
                matchedPlan = order.getPlanCode();
                matchedCode = order.getOrderCode();
                log.info("Order {} PAID: +{} credits to userId={}, amount={} VND, sepayId={}",
                        order.getOrderCode(), order.getCredits(), order.getUserId(),
                        payload.getTransferAmount(), payload.getId());
            } else {
                log.info("Order {} was settled concurrently, sepayId={}", order.getOrderCode(), payload.getId());
                matchedCode = order.getOrderCode();
            }
        }

        saveTransaction(payload, matchedUserId, creditsGranted, matchedPlan, matchedCode);
    }

    private String extractOrderCode(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = orderCodePattern().matcher(text);
        return matcher.find() ? matcher.group().toUpperCase() : null;
    }

    private String firstOrderCode(String... candidates) {
        for (String candidate : candidates) {
            String orderCode = extractOrderCode(candidate);
            if (orderCode != null) {
                return orderCode;
            }
        }
        return null;
    }

    private Pattern orderCodePattern() {
        String currentPrefix = sepayProperties.getNormalizedOrderCodePrefix();
        String prefixRegex = Pattern.quote(currentPrefix);
        if (!"CHIP".equals(currentPrefix)) {
            prefixRegex = "(?:" + prefixRegex + "|CHIP)";
        }
        return Pattern.compile(prefixRegex + "[0-9A-F]{8}", Pattern.CASE_INSENSITIVE);
    }

    private void saveTransaction(SepayWebhookPayload payload, Long userId,
                                 int creditsGranted, String planCode, String orderCode) {
        LocalDateTime txDate = null;
        if (payload.getTransactionDate() != null) {
            try {
                txDate = LocalDateTime.parse(payload.getTransactionDate(), SEPAY_DATE_FMT);
            } catch (Exception e) {
                log.debug("Failed to parse transactionDate: {}", payload.getTransactionDate());
            }
        }

        PaymentTransaction tx = PaymentTransaction.builder()
                .sepayTransactionId(payload.getId())
                .userId(userId)
                .gateway(payload.getGateway())
                .transactionDate(txDate)
                .accountNumber(payload.getAccountNumber())
                .code(payload.getCode())
                .content(payload.getContent())
                .transferType(payload.getTransferType())
                .transferAmount(payload.getTransferAmount())
                .referenceCode(payload.getReferenceCode())
                .creditsGranted(creditsGranted)
                .planCode(planCode)
                .orderCode(orderCode)
                .build();

        paymentRepo.save(tx);
    }
}
