package com.tranbac.chiptripbe.module.external.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DirectionResponse {
    private int distanceMeters;
    private int durationSeconds;
    /** Goong encoded polyline (Google polyline format) — FE decode để vẽ tuyến. */
    private String overviewPolyline;
}
