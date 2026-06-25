package com.tranbac.chiptripbe.module.trip.repository;

import com.tranbac.chiptripbe.module.trip.entity.ActivityAlternativeSession;
import com.tranbac.chiptripbe.module.trip.enums.ActivityAlternativeCategory;
import com.tranbac.chiptripbe.module.trip.enums.ActivityAlternativeSessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

public interface ActivityAlternativeSessionRepository extends JpaRepository<ActivityAlternativeSession, Long> {
    Optional<ActivityAlternativeSession> findByIdAndUserIdAndTripIdAndDayIdAndActivityId(
            Long id, Long userId, Long tripId, Long dayId, Long activityId);

    Optional<ActivityAlternativeSession> findFirstByUserIdAndTripIdAndDayIdAndActivityIdAndStatusAndExpiresAtAfterOrderByIdDesc(
            Long userId,
            Long tripId,
            Long dayId,
            Long activityId,
            ActivityAlternativeSessionStatus status,
            LocalDateTime now);

    Optional<ActivityAlternativeSession> findFirstByUserIdAndTripIdAndDayIdAndActivityIdAndCategoryAndStatusAndExpiresAtAfterOrderByIdDesc(
            Long userId,
            Long tripId,
            Long dayId,
            Long activityId,
            ActivityAlternativeCategory category,
            ActivityAlternativeSessionStatus status,
            LocalDateTime now);

    void deleteByTripId(Long tripId);

    void deleteByActivityId(Long activityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT session
            FROM ActivityAlternativeSession session
            WHERE session.id = :id
              AND session.userId = :userId
              AND session.tripId = :tripId
              AND session.dayId = :dayId
              AND session.activityId = :activityId
            """)
    Optional<ActivityAlternativeSession> findByIdAndUserIdAndTripIdAndDayIdAndActivityIdForUpdate(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("tripId") Long tripId,
            @Param("dayId") Long dayId,
            @Param("activityId") Long activityId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActivityAlternativeSession session
            SET session.status = :expiredStatus
            WHERE session.userId = :userId
              AND session.tripId = :tripId
              AND session.activityId IN :activityIds
              AND session.status = :pendingStatus
              AND session.id <> :keepSessionId
            """)
    int expireOtherPendingSessionsForActivities(
            @Param("userId") Long userId,
            @Param("tripId") Long tripId,
            @Param("activityIds") Collection<Long> activityIds,
            @Param("keepSessionId") Long keepSessionId,
            @Param("pendingStatus") ActivityAlternativeSessionStatus pendingStatus,
            @Param("expiredStatus") ActivityAlternativeSessionStatus expiredStatus);
}
