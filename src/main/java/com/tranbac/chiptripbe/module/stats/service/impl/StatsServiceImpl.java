package com.tranbac.chiptripbe.module.stats.service.impl;

import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.stats.dto.response.AiCostByProviderMonthResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.DailyCountResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.DashboardResponse;
import com.tranbac.chiptripbe.module.stats.service.StatsService;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class StatsServiceImpl implements StatsService {

    private static final LocalDate DATE_MIN = LocalDate.of(2000, 1, 1);

    private final UserRepository userRepository;
    private final TripRepository tripRepository;
    private final AiUsageRepository aiUsageRepository;

    @Override
    public DashboardResponse getDashboard() {
        long totalUsers = userRepository.count();
        long totalTrips = tripRepository.count();

        YearMonth currentMonth = YearMonth.now();
        LocalDateTime startOfMonth = currentMonth.atDay(1).atStartOfDay();
        LocalDateTime endOfMonth = currentMonth.atEndOfMonth().atTime(LocalTime.MAX);

        Object[] aiStats = aiUsageRepository.aggregateForPeriod(startOfMonth, endOfMonth);
        BigDecimal aiCost = new BigDecimal(aiStats[0].toString());
        long aiCalls = ((Number) aiStats[1]).longValue();

        log.debug("Dashboard stats: users={}, trips={}, aiCalls={}", totalUsers, totalTrips, aiCalls);
        return DashboardResponse.builder()
                .totalUsers(totalUsers)
                .totalTrips(totalTrips)
                .aiCallsThisMonth(aiCalls)
                .aiCostUsdThisMonth(aiCost)
                .build();
    }

    @Override
    public List<DailyCountResponse> getUserRegistrationsByDay(LocalDate from, LocalDate to) {
        LocalDateTime start = (from != null ? from : DATE_MIN).atStartOfDay();
        LocalDateTime end = (to != null ? to : LocalDate.now()).atTime(LocalTime.MAX);
        return userRepository.countRegistrationsByDay(start, end).stream()
                .map(row -> DailyCountResponse.builder()
                        .date(String.format("%04d-%02d-%02d",
                                ((Number) row[0]).intValue(),
                                ((Number) row[1]).intValue(),
                                ((Number) row[2]).intValue()))
                        .count(((Number) row[3]).longValue())
                        .build())
                .toList();
    }

    @Override
    public List<DailyCountResponse> getTripCreationsByDay(LocalDate from, LocalDate to) {
        LocalDateTime start = (from != null ? from : DATE_MIN).atStartOfDay();
        LocalDateTime end = (to != null ? to : LocalDate.now()).atTime(LocalTime.MAX);
        return tripRepository.countCreationsByDay(start, end).stream()
                .map(row -> DailyCountResponse.builder()
                        .date(String.format("%04d-%02d-%02d",
                                ((Number) row[0]).intValue(),
                                ((Number) row[1]).intValue(),
                                ((Number) row[2]).intValue()))
                        .count(((Number) row[3]).longValue())
                        .build())
                .toList();
    }

    @Override
    public List<AiCostByProviderMonthResponse> getAiCostByProviderMonth(LocalDate from, LocalDate to) {
        LocalDateTime start = (from != null ? from : DATE_MIN).atStartOfDay();
        LocalDateTime end = (to != null ? to : LocalDate.now()).atTime(LocalTime.MAX);
        return aiUsageRepository.aggregateCostByProviderMonth(start, end).stream()
                .map(row -> AiCostByProviderMonthResponse.builder()
                        .provider((String) row[0])
                        .month(String.format("%04d-%02d",
                                ((Number) row[1]).intValue(),
                                ((Number) row[2]).intValue()))
                        .usageCount(((Number) row[3]).longValue())
                        .totalTokensIn(((Number) row[4]).longValue())
                        .totalTokensOut(((Number) row[5]).longValue())
                        .totalCostUsd(new BigDecimal(row[6].toString()))
                        .build())
                .toList();
    }
}
