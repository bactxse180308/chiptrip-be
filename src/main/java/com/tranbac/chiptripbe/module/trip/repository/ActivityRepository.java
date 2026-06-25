package com.tranbac.chiptripbe.module.trip.repository;

import com.tranbac.chiptripbe.module.trip.entity.Activity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ActivityRepository extends JpaRepository<Activity, Long> {
    List<Activity> findByDayIdOrderByDisplayOrder(Long dayId);

    /** Khóa ghi các activity của 1 ngày để reorder an toàn (chống chèn/đổi thứ tự song song). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Activity a WHERE a.day.id = :dayId ORDER BY a.displayOrder")
    List<Activity> findByDayIdForUpdateOrderByDisplayOrder(@Param("dayId") Long dayId);
    List<Activity> findByDayTripIdOrderByDayDayNumberAscDisplayOrderAsc(Long tripId);
    Optional<Activity> findByIdAndDayTripUserId(Long id, Long userId);

    @Query("SELECT a FROM Activity a JOIN FETCH a.day d WHERE d.trip.id = :tripId ORDER BY d.dayNumber, a.displayOrder")
    List<Activity> findByTripIdWithDayOrderByDayAndOrder(@Param("tripId") Long tripId);

    /**
     * Returns [[tripId, imageUrl]] for the first non-null image per trip, given a list of trip IDs.
     * Uses correlated subquery pattern to avoid N+1 queries in getMyTrips.
     */
    @Query(nativeQuery = true, value =
        "SELECT td.trip_id, a.image_url " +
        "FROM activities a " +
        "JOIN trip_days td ON a.day_id = td.id " +
        "WHERE td.trip_id IN :tripIds " +
        "  AND a.image_url IS NOT NULL " +
        "  AND a.id = (" +
        "    SELECT TOP 1 a2.id FROM activities a2 " +
        "    JOIN trip_days td2 ON a2.day_id = td2.id " +
        "    WHERE td2.trip_id = td.trip_id AND a2.image_url IS NOT NULL " +
        "    ORDER BY td2.day_number, a2.display_order" +
        "  )")
    List<Object[]> findFirstImageUrlsForTrips(@Param("tripIds") List<Long> tripIds);
}
