package com.tranbac.chiptripbe.module.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateOrderRequest {

    @NotBlank(message = "planCode is required")
    private String planCode;
}
