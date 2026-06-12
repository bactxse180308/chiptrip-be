package com.tranbac.chiptripbe.module.place.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.module.place.dto.PlaceDto;
import com.tranbac.chiptripbe.module.place.service.PlaceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoint lấy chi tiết địa điểm đã enrich (rating, ảnh, giờ mở cửa).
 * Frontend gọi lazy khi user mở chi tiết một activity trong lịch trình.
 */
@Tag(name = "Places", description = "Thông tin địa điểm đã enrich từ Goong + SerpApi")
@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceQueryService placeQueryService;

    @Operation(summary = "Lấy chi tiết địa điểm theo cache ID")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}")
    public ApiResponse<PlaceDto> getPlace(@PathVariable Long id) {
        return ApiResponse.ok(placeQueryService.getPlace(id));
    }
}
