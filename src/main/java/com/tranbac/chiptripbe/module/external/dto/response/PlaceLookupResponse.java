package com.tranbac.chiptripbe.module.external.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Kết quả tra cứu chi tiết 1 địa điểm theo Goong place_id (sau khi user chọn 1 autocomplete prediction).
 * Cho phép FE lấy lat/lng + thông tin hành chính chính xác để truyền vào generate trip.
 */
@Getter
@Builder
public class PlaceLookupResponse {
    private String placeId;
    private String formattedAddress;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String provinceName;
    private String communeName;
}
