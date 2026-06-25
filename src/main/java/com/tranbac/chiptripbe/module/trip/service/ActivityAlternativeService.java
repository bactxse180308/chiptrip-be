package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.trip.dto.request.ActivityAlternativesRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.ReplaceActivityRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.ActivityAlternativesResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.ReplaceActivityResponse;

public interface ActivityAlternativeService {
    ActivityAlternativesResponse getActiveAlternatives(Long userId, Long tripId, Long dayId, Long activityId);

    ActivityAlternativesResponse createAlternatives(Long userId, Long tripId, Long dayId, Long activityId,
                                                    ActivityAlternativesRequest request);

    ReplaceActivityResponse replaceActivity(Long userId, Long tripId, Long dayId, Long activityId,
                                            ReplaceActivityRequest request);
}
