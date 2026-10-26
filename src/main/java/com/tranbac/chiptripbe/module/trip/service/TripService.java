package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerateResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface TripService {

    TripGenerateResponse generateTrip(Long userId, GenerateTripRequest request);

    /**
     * Chạy fail-fast các kiểm tra (giới hạn tier, ngày, còn lượt AI) đồng bộ trước khi
     * giao việc nặng cho worker bất đồng bộ — để controller trả ngay 402/400/403 và dính rate limit.
     */
    void validateGenerateRequest(Long userId, GenerateTripRequest request);

    Page<TripSummaryResponse> getMyTrips(Long userId, Pageable pageable);

    TripDetailResponse getTripDetail(Long userId, Long tripId);

    TripDetailResponse updateTrip(Long userId, Long tripId, UpdateTripRequest request);

    void deleteTrip(Long userId, Long tripId);

    TripDetailResponse cloneTrip(Long userId, Long tripId);


}
