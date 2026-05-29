package com.tranbac.chiptripbe.module.ai.service;

import com.tranbac.chiptripbe.module.ai.dto.response.UserAiUsageResponse;

public interface AiUsageService {
    UserAiUsageResponse getUserAiUsage(Long userId);
}
