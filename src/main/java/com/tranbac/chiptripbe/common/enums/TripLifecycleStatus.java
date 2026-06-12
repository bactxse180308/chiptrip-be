package com.tranbac.chiptripbe.common.enums;

import java.time.LocalDate;

/**
 * Trạng thái vòng đời chuyến đi — DERIVED từ dateStart/dateEnd so với hôm nay.
 * KHÔNG lưu cột DB; tính khi build response để luôn đúng theo thời gian thực.
 */
public enum TripLifecycleStatus {
    UPCOMING,   // hôm nay < dateStart
    ONGOING,    // dateStart <= hôm nay <= dateEnd
    COMPLETED;  // hôm nay > dateEnd

    public static TripLifecycleStatus of(LocalDate dateStart, LocalDate dateEnd, LocalDate today) {
        if (dateStart != null && today.isBefore(dateStart)) return UPCOMING;
        if (dateEnd != null && today.isAfter(dateEnd)) return COMPLETED;
        return ONGOING;
    }
}
