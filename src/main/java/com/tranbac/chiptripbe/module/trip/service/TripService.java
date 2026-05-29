package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.ShareTokenResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerateResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface TripService {

    TripGenerateResponse generateTrip(Long userId, GenerateTripRequest request);

    Page<TripSummaryResponse> getMyTrips(Long userId, Pageable pageable);

    TripDetailResponse getTripDetail(Long userId, Long tripId);

    TripDetailResponse updateTrip(Long userId, Long tripId, UpdateTripRequest request);

    void deleteTrip(Long userId, Long tripId);

    TripDetailResponse cloneTrip(Long userId, Long tripId);

    ShareTokenResponse enableShare(Long userId, Long tripId);

    void disableShare(Long userId, Long tripId);

    TripDetailResponse getSharedTrip(String shareToken);
}
