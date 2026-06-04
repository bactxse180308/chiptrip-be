package com.tranbac.chiptripbe.module.ai.service;

import com.tranbac.chiptripbe.module.ai.dto.response.AiUsageResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.AiCostByProviderMonthResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDate;
import java.util.List;

public interface AdminAiUsageService {

    Page<AiUsageResponse> getAllUsages(Long userId, String provider, LocalDate from, LocalDate to, Pageable pageable);

    List<AiCostByProviderMonthResponse> getSummary(LocalDate from, LocalDate to);
}
