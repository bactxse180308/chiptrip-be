package com.tranbac.chiptripbe.module.trip.repository;

import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TripDayRepository extends JpaRepository<TripDay, Long> {
    List<TripDay> findByTripIdOrderByDayNumber(Long tripId);
    Optional<TripDay> findByIdAndTripId(Long id, Long tripId);
}
