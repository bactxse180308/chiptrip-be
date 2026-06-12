package com.tranbac.chiptripbe.module.flight.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.flight.dto.FlightSuggestionResponse;
import com.tranbac.chiptripbe.module.flight.service.FlightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Flights", description = "Gợi ý chuyến bay cho chuyến đi")
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class FlightController {

    private final FlightService flightService;

    @Operation(summary = "Gợi ý chuyến bay (điểm đi → điểm đến) cho chuyến đi")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}/flights")
    public ResponseEntity<ApiResponse<FlightSuggestionResponse>> getFlights(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(flightService.getFlightSuggestion(principal.getId(), id)));
    }
}
