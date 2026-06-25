package com.tranbac.chiptripbe.module.trip.repository;

import com.tranbac.chiptripbe.module.trip.entity.Trip;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long>, JpaSpecificationExecutor<Trip> {

    Page<Trip> findByUserId(Long userId, Pageable pageable);

    Optional<Trip> findByShareToken(String shareToken);

    Optional<Trip> findByInviteToken(String inviteToken);

    Optional<Trip> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trip t WHERE t.id = :tripId AND t.user.id = :userId")
    Optional<Trip> findByIdAndUserIdForUpdate(@Param("tripId") Long tripId, @Param("userId") Long userId);

    List<Trip> findByDateStart(java.time.LocalDate dateStart);

    /** Cho PostTripScheduler: chuyến vừa kết thúc (dateEnd = hôm qua). */
    List<Trip> findByDateEnd(java.time.LocalDate dateEnd);

    /** Lấy các trip có dateStart trong [from, to] (inclusive) cho weather-alert scheduler. */
    List<Trip> findByDateStartBetween(java.time.LocalDate from, java.time.LocalDate to);

    /** Feed công khai — ORDER BY published_at DESC. */
    Page<Trip> findByIsPublicTrueOrderByPublishedAtDesc(Pageable pageable);

    Page<Trip> findByIsPublicTrueAndDestinationContainingIgnoreCaseOrderByPublishedAtDesc(
            String destination, Pageable pageable);

    /** Featured feed: public trips in a recent window, ordered by likes first. */
    Page<Trip> findByIsPublicTrueAndPublishedAtGreaterThanEqualOrderByLikesCountDescPublishedAtDesc(
            LocalDateTime cutoff, Pageable pageable);

    Page<Trip> findByIsPublicTrueAndPublishedAtGreaterThanEqualAndDestinationContainingIgnoreCaseOrderByLikesCountDescPublishedAtDesc(
            LocalDateTime cutoff, String destination, Pageable pageable);

    long countByIsPublicTrue();

    /** Atomic update: avoid lost update when users like/comment concurrently. */
    @Modifying
    @Query("UPDATE Trip t SET t.likesCount = t.likesCount + :delta WHERE t.id = :tripId")
    void updateLikesCount(@Param("tripId") Long tripId, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Trip t SET t.commentsCount = t.commentsCount + :delta WHERE t.id = :tripId")
    void updateCommentsCount(@Param("tripId") Long tripId, @Param("delta") int delta);

    @Query("SELECT YEAR(t.createdAt), MONTH(t.createdAt), DAY(t.createdAt), COUNT(t) " +
           "FROM Trip t WHERE t.createdAt >= :from AND t.createdAt <= :to " +
           "GROUP BY YEAR(t.createdAt), MONTH(t.createdAt), DAY(t.createdAt) " +
           "ORDER BY YEAR(t.createdAt), MONTH(t.createdAt), DAY(t.createdAt)")
    List<Object[]> countCreationsByDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
