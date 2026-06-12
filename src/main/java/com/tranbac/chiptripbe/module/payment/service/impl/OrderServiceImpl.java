package com.tranbac.chiptripbe.module.payment.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.payment.config.SepayProperties;
import com.tranbac.chiptripbe.module.payment.config.SepayProperties.PricingPlan;
import com.tranbac.chiptripbe.module.payment.dto.PaymentOrderResponse;
import com.tranbac.chiptripbe.module.payment.dto.PaymentPlanResponse;
import com.tranbac.chiptripbe.module.payment.entity.OrderStatus;
import com.tranbac.chiptripbe.module.payment.entity.PaymentOrder;
import com.tranbac.chiptripbe.module.payment.repository.PaymentOrderRepository;
import com.tranbac.chiptripbe.module.payment.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class OrderServiceImpl implements OrderService {

    private final PaymentOrderRepository orderRepository;
    private final SepayProperties sepayProperties;

    @Override
    @Transactional(readOnly = true)
    public List<PaymentPlanResponse> listPlans() {
        return sepayProperties.getPlans().entrySet().stream()
                .map(e -> new PaymentPlanResponse(e.getKey(), e.getValue().getPriceVnd(), e.getValue().getCredits()))
                .toList();
    }

    @Override
    @Transactional
    public PaymentOrderResponse createOrder(Long userId, String planCode) {
        String normalized = planCode == null ? "" : planCode.toUpperCase();
        PricingPlan plan = sepayProperties.findPlan(normalized);
        if (plan == null) {
            throw AppException.badRequest("Gói không hợp lệ: " + planCode);
        }

        LocalDateTime now = LocalDateTime.now();
        PaymentOrder order = PaymentOrder.builder()
                .orderCode(generateUniqueOrderCode())
                .userId(userId)
                .planCode(normalized)
                .amountVnd(plan.getPriceVnd())
                .credits(plan.getCredits())
                .status(OrderStatus.PENDING)
                .createdAt(now)
                .expiresAt(now.plusMinutes(sepayProperties.getOrderExpiryMinutes()))
                .build();

        order = orderRepository.save(order);
        log.info("Created payment order code={}, plan={}, amount={}, userId={}",
                order.getOrderCode(), normalized, plan.getPriceVnd(), userId);
        return toResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentOrderResponse getOrder(Long userId, Long orderId) {
        PaymentOrder order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy đơn hàng"));
        return toResponse(order);
    }

    private String generateUniqueOrderCode() {
        String prefix = sepayProperties.getNormalizedOrderCodePrefix();
        for (int i = 0; i < 5; i++) {
            String code = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            if (!orderRepository.existsByOrderCode(code)) {
                return code;
            }
        }
        throw AppException.internal("Không sinh được mã đơn hàng");
    }

    private String buildQrUrl(PaymentOrder order) {
        return UriComponentsBuilder.fromUriString(sepayProperties.getQrBaseUrl())
                .queryParam("acc", sepayProperties.getBankAccount())
                .queryParam("bank", sepayProperties.getBankCode())
                .queryParam("amount", order.getAmountVnd())
                .queryParam("des", order.getOrderCode())
                .queryParam("template", sepayProperties.getQrTemplate())
                .build()
                .encode()
                .toUriString();
    }

    private PaymentOrderResponse toResponse(PaymentOrder order) {
        return PaymentOrderResponse.builder()
                .orderId(order.getId())
                .orderCode(order.getOrderCode())
                .planCode(order.getPlanCode())
                .amountVnd(order.getAmountVnd())
                .credits(order.getCredits())
                .status(order.getStatus())
                .qrUrl(buildQrUrl(order))
                .bankName(sepayProperties.getBankCode())
                .accountNumber(sepayProperties.getBankAccount())
                .accountHolder(sepayProperties.getAccountHolder())
                .transferContent(order.getOrderCode())
                .createdAt(order.getCreatedAt())
                .expiresAt(order.getExpiresAt())
                .paidAt(order.getPaidAt())
                .build();
    }
}
