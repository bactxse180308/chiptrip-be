package com.tranbac.chiptripbe.module.payment.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.payment.dto.CreateOrderRequest;
import com.tranbac.chiptripbe.module.payment.dto.PaymentOrderResponse;
import com.tranbac.chiptripbe.module.payment.dto.PaymentPlanResponse;
import com.tranbac.chiptripbe.module.payment.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Payment", description = "Mua thêm lượt AI qua SePay (VietQR)")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final OrderService orderService;

    @Operation(summary = "Danh sách gói nạp lượt AI")
    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<List<PaymentPlanResponse>>> listPlans() {
        return ResponseEntity.ok(ApiResponse.ok(orderService.listPlans()));
    }

    @Operation(summary = "Tạo đơn hàng + sinh mã VietQR")
    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<PaymentOrderResponse>> createOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateOrderRequest request) {
        PaymentOrderResponse order = orderService.createOrder(principal.getId(), request.getPlanCode());
        return ResponseEntity.ok(ApiResponse.created(order));
    }

    @Operation(summary = "Trạng thái đơn hàng (FE poll để biết đã thanh toán chưa)")
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<PaymentOrderResponse>> getOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.getOrder(principal.getId(), orderId)));
    }
}
