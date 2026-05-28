package com.tranbac.chiptripbe.module.trip.repository;

import com.tranbac.chiptripbe.module.trip.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ActivityRepository extends JpaRepository<Activity, Long> {
    List<Activity> findByDayIdOrderByDisplayOrder(Long dayId);
    Optional<Activity> findByIdAndDayTripUserId(Long id, Long userId);
}