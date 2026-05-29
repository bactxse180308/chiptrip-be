package com.tranbac.chiptripbe.module.ai.service.impl;

import com.tranbac.chiptripbe.module.ai.dto.response.UserAiUsageResponse;
import com.tranbac.chiptripbe.module.ai.entity.AiUsage;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.ai.service.AiUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AiUsageServiceImpl implements AiUsageService {

    private final AiUsageRepository aiUsageRepository;

    @Override
    public UserAiUsageResponse getUserAiUsage(Long userId) {
        List<AiUsage> usages = aiUsageRepository.findByUserId(userId, PageRequest.of(0, 50)).getContent();

        long totalCount = usages.size();
        long totalTokensIn = 0L;
        long totalTokensOut = 0L;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (AiUsage u : usages) {
            totalTokensIn += u.getTokensIn();
            totalTokensOut += u.getTokensOut();
            totalCost = totalCost.add(u.getCostUsd());
        }

        final long finalTokensIn = totalTokensIn;
        final long finalTokensOut = totalTokensOut;
        final BigDecimal finalCost = totalCost;

        List<UserAiUsageResponse.AiUsageDetail> details = usages.stream().map(u -> {
            String tripTitle = u.getTrip() != null ? u.getTrip().getTitle() : null;
            Long tripId = u.getTrip() != null ? u.getTrip().getId() : null;

            return UserAiUsageResponse.AiUsageDetail.builder()
                    .id(u.getId())
                    .tripId(tripId)
                    .tripTitle(tripTitle)
                    .provider(u.getProvider())
                    .tokensIn(u.getTokensIn())
                    .tokensOut(u.getTokensOut())
                    .costUsd(u.getCostUsd())
                    .createdAt(u.getCreatedAt())
                    .build();
        }).toList();

        UserAiUsageResponse.LongSummary summary = UserAiUsageResponse.LongSummary.builder()
                .totalCount(totalCount)
                .totalTokensIn(finalTokensIn)
                .totalTokensOut(finalTokensOut)
                .totalCostUsd(finalCost)
                .build();

        return UserAiUsageResponse.builder()
                .usages(details)
                .summary(summary)
                .build();
    }
}
