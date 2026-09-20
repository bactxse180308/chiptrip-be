package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.ShareTokenResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripReadOnlyResponse;
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

    ShareTokenResponse enableShare(Long userId, Long tripId);

    void disableShare(Long userId, Long tripId);

    TripReadOnlyResponse getSharedTrip(String shareToken);

    /** Xem trip public (feed Khám phá) — trả 404 nếu trip không tồn tại hoặc is_public=false. */
    TripReadOnlyResponse getPublicTrip(Long tripId);

    /**
     * Bảo trì nội bộ (gọi bởi TripEnrichmentScheduler): quét các trip còn enrichmentPending mà job
     * enrich nền in-memory đã mất (app restart / queue đầy / provider down) rồi enrich lại từ DB.
     * Durable safety-net cho enrich nền ngày 2..N.
     */
    void sweepPendingEnrichments();
}
