package com.tranbac.chiptripbe.module.ai.service;

import com.tranbac.chiptripbe.module.ai.dto.AiCallResult;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;

public interface AiService {
    AiCallResult generateItinerary(GenerateTripRequest request);
}