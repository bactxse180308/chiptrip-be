package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDate;

public interface AdminTripService {

    Page<TripSummaryResponse> getAllTrips(Long userId, LocalDate from, LocalDate to, Pageable pageable);

    TripDetailResponse getTripDetail(Long tripId);

    void deleteTrip(Long tripId);
}
