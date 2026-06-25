package com.tranbac.chiptripbe.module.trip.dto.response;

import com.tranbac.chiptripbe.common.enums.ActivityType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityAlternativeOptionResponse {
    private String optionId;
    private String name;
    private String description;
    private ActivityType type;
    private Long costVnd;
    private String searchQuery;
    private String reason;
    private Long placeCacheId;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal rating;
    private Integer reviewCount;
    private String imageUrl;
    private String bookingUrl;
    private String openState;
}
