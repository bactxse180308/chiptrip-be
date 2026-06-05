package com.tranbac.chiptripbe.module.ai.service;

import com.tranbac.chiptripbe.module.ai.dto.request.SuggestDestinationsRequest;
import com.tranbac.chiptripbe.module.ai.dto.response.DestinationSuggestion;

import java.util.List;

public interface AiSuggestService {

    /** Gợi ý 3-5 điểm đến phù hợp. KHÔNG trừ aiCredits. */
    List<DestinationSuggestion> suggest(Long userId, SuggestDestinationsRequest request);
}
