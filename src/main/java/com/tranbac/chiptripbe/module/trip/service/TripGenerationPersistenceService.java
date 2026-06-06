package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.ai.dto.AiCallResult;
import com.tranbac.chiptripbe.module.ai.dto.AiItineraryResult;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerateResponse;

import java.util.Map;

/**
 * Phase persist của luồng generate trip.
 *
 * Tách riêng để tránh giữ DB transaction trong lúc gọi Gemini và resolve place
 * (cả 2 đều là HTTP call dài). Caller (TripServiceImpl.generateTrip) chịu trách
 * nhiệm gọi AI + resolve place trước khi gọi method này.
 */
public interface TripGenerationPersistenceService {

    /**
     * Persist trip + days + activities + checklist + AiUsage trong 1 transaction;
     * trừ aiCredits của user; publish AiCreditsLowEvent nếu credits còn ≤ 1.
     *
     * Tiền điều kiện: aiResult đã được validate hợp lệ; resolvedPlaces đã geocode xong.
     * Method này không gọi LLM cũng không gọi geocoding API.
     *
     * @param resolvedPlaces identity-map từ AiActivity → PlaceCache đã resolve (có thể không
     *                       chứa entry nếu activity không cần / không geocode được)
     */
    TripGenerateResponse persistGeneratedTrip(Long userId,
                                              GenerateTripRequest request,
                                              AiCallResult aiResult,
                                              Map<AiItineraryResult.AiActivity, PlaceCache> resolvedPlaces);
}
