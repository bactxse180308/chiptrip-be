package com.tranbac.chiptripbe.module.flight.service;

import com.tranbac.chiptripbe.module.flight.dto.FlightSuggestionResponse;

public interface FlightService {

    /** Gợi ý chuyến bay cho trip (kiểm tra quyền sở hữu). Cache ngắn hạn. */
    FlightSuggestionResponse getFlightSuggestion(Long userId, Long tripId);
}
