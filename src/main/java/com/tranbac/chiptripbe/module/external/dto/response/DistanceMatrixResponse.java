package com.tranbac.chiptripbe.module.external.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class DistanceMatrixResponse {
    /** Cùng size với origins. null nếu Goong không trả OK cho cặp đó. */
    private List<Segment> segments;

    @Getter
    @Builder
    public static class Segment {
        private int distanceMeters;
        private int durationSeconds;
    }
}
