package com.tranbac.chiptripbe.module.geocoding.dto;

import java.math.BigDecimal;

public record GeocodingResult(
        String placeId,
        String name,
        String formattedAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        String provider
) {}