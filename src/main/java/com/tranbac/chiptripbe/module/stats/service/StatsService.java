package com.tranbac.chiptripbe.module.stats.service;

import com.tranbac.chiptripbe.module.stats.dto.response.AiCostByProviderMonthResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.DailyCountResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.DailyRevenueResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.DashboardResponse;

import java.time.LocalDate;
import java.util.List;

public interface StatsService {

    DashboardResponse getDashboard();

    List<DailyCountResponse> getUserRegistrationsByDay(LocalDate from, LocalDate to);

    List<DailyCountResponse> getTripCreationsByDay(LocalDate from, LocalDate to);

    List<AiCostByProviderMonthResponse> getAiCostByProviderMonth(LocalDate from, LocalDate to);

    List<DailyRevenueResponse> getRevenueByDay(LocalDate from, LocalDate to);

    List<DailyCountResponse> getAiCallsByDay(LocalDate from, LocalDate to);
}
