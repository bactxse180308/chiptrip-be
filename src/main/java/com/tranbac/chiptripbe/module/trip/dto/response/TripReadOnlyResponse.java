package com.tranbac.chiptripbe.module.trip.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tranbac.chiptripbe.common.enums.ActivityType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Explicit allowlist for public/shared readers; never inherits the owner's DTO. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripReadOnlyResponse(
        Long id, String title, String departure, String destination,
        LocalDate dateStart, LocalDate dateEnd, Integer peopleCount, String styles,
        Long totalCostVnd, Boolean isPublic, Integer likesCount, Integer commentsCount,
        String status, boolean enriching, UserInfo user, List<Day> days) {

    public record UserInfo(Long id, String fullName, String avatarUrl) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Day(Long id, Integer dayNumber, LocalDate date, Long dayCostVnd, List<Activity> activities) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Activity(Long id, LocalTime startTime, String name, String description,
            ActivityType type, Long costVnd, BigDecimal latitude, BigDecimal longitude,
            String imageUrl, String bookingUrl, Integer displayOrder, Long placeCacheId, String address) { }
}
