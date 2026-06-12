package com.tranbac.chiptripbe.module.payment.service;

import com.tranbac.chiptripbe.module.payment.dto.PaymentOrderResponse;
import com.tranbac.chiptripbe.module.payment.dto.PaymentPlanResponse;

import java.util.List;

public interface OrderService {

    List<PaymentPlanResponse> listPlans();

    PaymentOrderResponse createOrder(Long userId, String planCode);

    PaymentOrderResponse getOrder(Long userId, Long orderId);
}
