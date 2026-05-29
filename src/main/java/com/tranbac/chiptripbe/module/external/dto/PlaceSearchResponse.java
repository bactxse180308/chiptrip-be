package com.tranbac.chiptripbe.module.external.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class PlaceSearchResponse {

    private List<Place> predictions;

    @Getter
    @Builder
    public static class Place {
        private String placeId;
        private String description;
        private String mainText;
        private String secondaryText;
        private BigDecimal latitude;
        private BigDecimal longitude;
    }
}
