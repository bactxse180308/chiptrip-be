package com.tranbac.chiptripbe.module.ai.service;

import com.tranbac.chiptripbe.module.ai.dto.response.AiUsageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDate;

public interface AdminAiUsageService {

    Page<AiUsageResponse> getAllUsages(Long userId, String provider, LocalDate from, LocalDate to, Pageable pageable);
}
