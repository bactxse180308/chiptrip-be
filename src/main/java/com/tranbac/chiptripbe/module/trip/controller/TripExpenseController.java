package com.tranbac.chiptripbe.module.trip.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.trip.dto.request.CreateTripExpenseRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripExpenseResponse;
import com.tranbac.chiptripbe.module.trip.service.TripExpenseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Trip Expenses", description = "Quản lý chi phí chuyến đi")
@RestController
@RequestMapping("/api/v1/trips/{tripId}/expenses")
@RequiredArgsConstructor
public class TripExpenseController {

    private final TripExpenseService tripExpenseService;

    @Operation(summary = "Danh sách chi phí")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<List<TripExpenseResponse>>> getExpenses(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId) {
        // Frontend might call this while checking permissions, the service will verify if the user has access.
        return ResponseEntity.ok(ApiResponse.ok(tripExpenseService.getExpensesByTrip(tripId, principal.getId())));
    }

    @Operation(summary = "Thêm chi phí")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<TripExpenseResponse>> createExpense(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @Valid @RequestBody CreateTripExpenseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(tripExpenseService.createExpense(tripId, principal.getId(), request)));
    }

    @Operation(summary = "Xóa chi phí")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{expenseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<ApiResponse<Void>> deleteExpense(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @PathVariable Long expenseId) {
        tripExpenseService.deleteExpense(tripId, expenseId, principal.getId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }
}
