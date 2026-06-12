package com.tranbac.chiptripbe.module.payment.dto;

public record PaymentPlanResponse(
        String code,
        Long priceVnd,
        Integer credits
) {}
