package com.tranbac.chiptripbe.module.ai.dto;

/** Wrapper kết quả từ LLM: chứa lịch trình parse được và số token để log AiUsage. */
public record AiCallResult(
        AiItineraryResult itinerary,
        int promptTokens,
        int completionTokens
) {}