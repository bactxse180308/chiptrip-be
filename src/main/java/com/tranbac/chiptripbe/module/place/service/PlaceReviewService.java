package com.tranbac.chiptripbe.module.place.service;

import com.tranbac.chiptripbe.module.place.dto.PlaceReviewRequest;
import com.tranbac.chiptripbe.module.place.dto.PlaceReviewResponse;
import com.tranbac.chiptripbe.module.place.dto.PlaceReviewSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PlaceReviewService {

    Page<PlaceReviewResponse> getReviews(Long placeCacheId, Pageable pageable);

    PlaceReviewSummaryResponse getSummary(Long placeCacheId);

    /** Mỗi user 1 review / địa điểm — nếu đã có thì UPDATE thay vì tạo mới. */
    PlaceReviewResponse upsertReview(Long userId, Long placeCacheId, PlaceReviewRequest request);

    void deleteReview(Long userId, Long placeCacheId, Long reviewId);
}
