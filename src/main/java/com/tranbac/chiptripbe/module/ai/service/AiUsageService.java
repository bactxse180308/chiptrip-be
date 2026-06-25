package com.tranbac.chiptripbe.module.ai.service;

import com.tranbac.chiptripbe.module.ai.dto.response.UserAiUsageResponse;

public interface AiUsageService {
    UserAiUsageResponse getUserAiUsage(Long userId);

    /**
     * Ghi 1 dòng audit chi phí LLM. Dùng cho các luồng gọi AI ngoài transaction persist
     * (vd gợi ý thay thế activity) để admin theo dõi token/chi phí thật.
     * Provider lấy từ cấu hình model hiện tại; chi phí tính theo app.ai.pricing.
     */
    void recordUsage(Long userId, Long tripId, int tokensIn, int tokensOut);
}
