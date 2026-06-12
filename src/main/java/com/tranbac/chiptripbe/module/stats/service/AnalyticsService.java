package com.tranbac.chiptripbe.module.stats.service;

import com.tranbac.chiptripbe.module.stats.dto.response.DailyCountResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.EventCountResponse;

import java.util.List;

/** Proxy số liệu PostHog (product analytics) — gọi HogQL phía server. */
public interface AnalyticsService {

    /** Lượt xem trang ($pageview) theo ngày trong N ngày gần nhất. */
    List<DailyCountResponse> getPageviewsByDay(int days);

    /** Tổng số lần phát sinh mỗi event (trừ $pageview) trong N ngày gần nhất. */
    List<EventCountResponse> getEventCounts(int days);

    /**
     * Funnel chuyển đổi: số người dùng (distinct) đạt mỗi bước, theo thứ tự cố định
     * sign_up → generate_started → generate_succeeded → trip_saved → booking_click →
     * publish → purchase_started → purchase_succeeded. N ngày gần nhất.
     */
    List<EventCountResponse> getFunnel(int days);
}
