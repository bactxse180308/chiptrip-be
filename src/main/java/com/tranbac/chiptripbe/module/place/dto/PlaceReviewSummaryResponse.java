package com.tranbac.chiptripbe.module.place.dto;

import lombok.Builder;
import lombok.Getter;

/** Tổng hợp rating cho tab "Đánh giá ChipTrip". */
@Getter
@Builder
public class PlaceReviewSummaryResponse {
    /** null nếu chưa có review nào. */
    private Double averageRating;
    private long totalReviews;
}
