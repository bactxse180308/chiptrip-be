package com.tranbac.chiptripbe.module.place.repository;

import com.tranbac.chiptripbe.module.place.entity.PlaceReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PlaceReviewRepository extends JpaRepository<PlaceReview, Long> {

    @EntityGraph(attributePaths = "user")
    Page<PlaceReview> findByPlaceCacheIdOrderByCreatedAtDesc(Long placeCacheId, Pageable pageable);

    Optional<PlaceReview> findByPlaceCacheIdAndUserId(Long placeCacheId, Long userId);

    /** [avgRating (Double|null), count (Long)] cho tab Đánh giá ChipTrip. */
    @Query("SELECT AVG(r.rating), COUNT(r) FROM PlaceReview r WHERE r.placeCacheId = :placeCacheId")
    Object[] findRatingSummary(@Param("placeCacheId") Long placeCacheId);
}
