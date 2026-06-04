package com.tranbac.chiptripbe.module.ai.service.impl;

import com.tranbac.chiptripbe.module.ai.dto.response.AiUsageResponse;
import com.tranbac.chiptripbe.module.ai.entity.AiUsage;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.ai.service.AdminAiUsageService;
import com.tranbac.chiptripbe.module.ai.specification.AiUsageSpecification;
import com.tranbac.chiptripbe.module.stats.dto.response.AiCostByProviderMonthResponse;
import com.tranbac.chiptripbe.module.stats.service.StatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AdminAiUsageServiceImpl implements AdminAiUsageService {

    private final AiUsageRepository aiUsageRepository;
    private final StatsService statsService;

    @Override
    public Page<AiUsageResponse> getAllUsages(Long userId, String provider, LocalDate from, LocalDate to, Pageable pageable) {
        Specification<AiUsage> spec = Specification.allOf();
        if (userId != null) {
            spec = spec.and(AiUsageSpecification.withUserId(userId));
        }
        if (StringUtils.hasText(provider)) {
            spec = spec.and(AiUsageSpecification.withProvider(provider));
        }
        if (from != null) {
            spec = spec.and(AiUsageSpecification.createdAfter(LocalDateTime.of(from, LocalTime.MIN)));
        }
        if (to != null) {
            spec = spec.and(AiUsageSpecification.createdBefore(LocalDateTime.of(to, LocalTime.MAX)));
        }
        return aiUsageRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Override
    public List<AiCostByProviderMonthResponse> getSummary(LocalDate from, LocalDate to) {
        return statsService.getAiCostByProviderMonth(from, to);
    }

    private AiUsageResponse toResponse(AiUsage a) {
        return AiUsageResponse.builder()
                .id(a.getId())
                .provider(a.getProvider())
                .tokensIn(a.getTokensIn())
                .tokensOut(a.getTokensOut())
                .costUsd(a.getCostUsd())
                .createdAt(a.getCreatedAt())
                .userId(a.getUser().getId())
                .userEmail(a.getUser().getEmail())
                .userFullName(a.getUser().getFullName())
                .tripId(a.getTrip() != null ? a.getTrip().getId() : null)
                .tripTitle(a.getTrip() != null ? a.getTrip().getTitle() : null)
                .build();
    }
}
