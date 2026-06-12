package com.tranbac.chiptripbe.module.trip.repository;

import com.tranbac.chiptripbe.module.trip.entity.TripLike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripLikeRepository extends JpaRepository<TripLike, Long> {

    boolean existsByTripIdAndUserId(Long tripId, Long userId);

    void deleteByTripIdAndUserId(Long tripId, Long userId);

    long countByTripId(Long tripId);

    /** Cleanup khi xóa trip (không có FK cascade vì cột tripId là Long thuần). */
    void deleteByTripId(Long tripId);
}
