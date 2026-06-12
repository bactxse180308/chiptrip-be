package com.tranbac.chiptripbe.module.trip.repository;

import com.tranbac.chiptripbe.module.trip.entity.TripComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripCommentRepository extends JpaRepository<TripComment, Long> {

    /** Root comments (parentId IS NULL), mới nhất trước, phân trang. */
    @EntityGraph(attributePaths = "user")
    Page<TripComment> findByTripIdAndParentIdIsNullOrderByCreatedAtDesc(Long tripId, Pageable pageable);

    /** Toàn bộ replies của trip trong 1 query — dựng tree in-memory, tránh N+1 theo độ sâu. */
    @EntityGraph(attributePaths = "user")
    List<TripComment> findByTripIdAndParentIdIsNotNullOrderByCreatedAtAsc(Long tripId);

    long countByTripId(Long tripId);

    /** Cleanup khi xóa trip (không có FK cascade vì cột tripId là Long thuần). */
    void deleteByTripId(Long tripId);
}
