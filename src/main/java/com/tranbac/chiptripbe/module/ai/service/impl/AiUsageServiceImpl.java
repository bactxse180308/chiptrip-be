package com.tranbac.chiptripbe.module.ai.service.impl;

import com.tranbac.chiptripbe.common.config.AiProperties;
import com.tranbac.chiptripbe.module.ai.dto.response.UserAiUsageResponse;
import com.tranbac.chiptripbe.module.ai.entity.AiUsage;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.ai.service.AiUsageService;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AiUsageServiceImpl implements AiUsageService {

    private final AiUsageRepository aiUsageRepository;
    private final UserRepository userRepository;
    private final TripRepository tripRepository;
    private final AiProperties aiProperties;

    // Cột provider giới hạn 30 ký tự (AiUsage.provider) — model id từ config có thể dài hơn
    private static final int PROVIDER_MAX_LENGTH = 30;

    @Override
    // REQUIRES_NEW: luôn chạy trong tx độc lập, để lỗi ghi audit không "đầu độc" tx của caller
    // (caller hiện tại — createAlternatives — là NOT_SUPPORTED, nhưng REQUIRES_NEW an toàn ở mọi caller).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUsage(Long userId, Long tripId, int tokensIn, int tokensOut) {
        BigDecimal costUsd = BigDecimal.valueOf(
                (double) tokensIn / 1_000_000 * aiProperties.getPricing().getInputUsdPer1m()
                        + (double) tokensOut / 1_000_000 * aiProperties.getPricing().getOutputUsdPer1m()
        ).setScale(6, RoundingMode.HALF_UP);

        aiUsageRepository.save(AiUsage.builder()
                .user(userRepository.getReferenceById(userId))
                .trip(tripId != null ? tripRepository.getReferenceById(tripId) : null)
                .provider(truncate(aiProperties.getOpenaiCompat().getModel(), PROVIDER_MAX_LENGTH))
                .tokensIn(tokensIn)
                .tokensOut(tokensOut)
                .costUsd(costUsd)
                .build());
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

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
