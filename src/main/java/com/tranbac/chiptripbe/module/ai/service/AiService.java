package com.tranbac.chiptripbe.module.ai.service;

import com.tranbac.chiptripbe.module.ai.dto.AiCallResult;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;

public interface AiService {
    /**
     * @param userPreferences gu du lịch đã lưu trong hồ sơ user (User.preferences,
     *                        chuỗi tag phân tách dấu phẩy, vd "food,photo") — null/blank nếu chưa có.
     */
    AiCallResult generateItinerary(GenerateTripRequest request, String userPreferences);
}