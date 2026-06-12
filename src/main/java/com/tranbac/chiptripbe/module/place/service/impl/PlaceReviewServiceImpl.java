package com.tranbac.chiptripbe.module.place.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.place.dto.PlaceReviewRequest;
import com.tranbac.chiptripbe.module.place.dto.PlaceReviewResponse;
import com.tranbac.chiptripbe.module.place.dto.PlaceReviewSummaryResponse;
import com.tranbac.chiptripbe.module.place.entity.PlaceReview;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.repository.PlaceReviewRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceReviewService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class PlaceReviewServiceImpl implements PlaceReviewService {

    private final PlaceReviewRepository placeReviewRepository;
    private final PlaceCacheRepository placeCacheRepository;
    private final UserRepository userRepository;

    @Override
    public Page<PlaceReviewResponse> getReviews(Long placeCacheId, Pageable pageable) {
        return placeReviewRepository.findByPlaceCacheIdOrderByCreatedAtDesc(placeCacheId, pageable)
                .map(this::toResponse);
    }

    @Override
    public PlaceReviewSummaryResponse getSummary(Long placeCacheId) {
        Object[] row = placeReviewRepository.findRatingSummary(placeCacheId);
        // JPA trả Object[]{Object[]{avg, count}} hoặc Object[]{avg, count} tùy version — unwrap an toàn
        if (row.length == 1 && row[0] instanceof Object[] inner) {
            row = inner;
        }
        Double avg = row[0] != null ? ((Number) row[0]).doubleValue() : null;
        long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
        return PlaceReviewSummaryResponse.builder()
                .averageRating(avg)
                .totalReviews(count)
                .build();
    }

    @Override
    @Transactional
    public PlaceReviewResponse upsertReview(Long userId, Long placeCacheId, PlaceReviewRequest request) {
        if (!placeCacheRepository.existsById(placeCacheId)) {
            throw AppException.notFound("Không tìm thấy địa điểm");
        }

        PlaceReview review = placeReviewRepository.findByPlaceCacheIdAndUserId(placeCacheId, userId)
                .orElse(null);
        if (review == null) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));
            review = PlaceReview.builder()
                    .placeCacheId(placeCacheId)
                    .user(user)
                    .build();
        }
        review.setRating(request.getRating());
        review.setContent(request.getContent());
        review = placeReviewRepository.save(review);

        log.info("User id={} reviewed placeCacheId={} rating={}", userId, placeCacheId, request.getRating());
        return toResponse(review);
    }

    @Override
    @Transactional
    public void deleteReview(Long userId, Long placeCacheId, Long reviewId) {
        PlaceReview review = placeReviewRepository.findById(reviewId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy đánh giá"));
        if (!review.getPlaceCacheId().equals(placeCacheId)) {
            throw AppException.notFound("Đánh giá không thuộc địa điểm này");
        }
        if (!review.getUser().getId().equals(userId)) {
            throw AppException.forbidden("Bạn không có quyền xóa đánh giá này");
        }
        placeReviewRepository.delete(review);
        log.info("User id={} deleted review id={} of placeCacheId={}", userId, reviewId, placeCacheId);
    }

    private PlaceReviewResponse toResponse(PlaceReview review) {
        return PlaceReviewResponse.builder()
                .id(review.getId())
                .userId(review.getUser().getId())
                .userName(review.getUser().getFullName())
                .userAvatarUrl(review.getUser().getAvatarUrl())
                .rating(review.getRating())
                .content(review.getContent())
                .createdAt(review.getCreatedAt())
                .build();
    }
}
