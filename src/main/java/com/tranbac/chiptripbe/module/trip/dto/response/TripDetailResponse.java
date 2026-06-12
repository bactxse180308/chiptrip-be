package com.tranbac.chiptripbe.module.trip.dto.response;

import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.common.enums.ChecklistCategory;
import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.ArrayList;

@Getter
@Builder
public class TripDetailResponse {

    private Long id;
    private String title;
    private String departure;
    private String destination;
    private LocalDate dateStart;
    private LocalDate dateEnd;
    private Integer peopleCount;
    private Long budgetVnd;
    private String styles;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long totalCostVnd;
    private String shareToken;
    private Boolean isPublic;
    private LocalDateTime publishedAt;
    private Integer likesCount;
    private Integer commentsCount;
    /** UPCOMING / ONGOING / COMPLETED — derived từ dateStart/dateEnd. */
    private String status;

    private UserInfo user;
    private List<TripMemberResponse> members;
    private List<DayDetail> days;
    private List<ChecklistItemDetail> checklist;

    @Getter
    @Builder
    public static class UserInfo {
        private Long id;
        private String email;
        private String fullName;
        private String avatarUrl;
    }

    @Getter
    @Builder
    public static class DayDetail {
        private Long id;
        private Integer dayNumber;
        private LocalDate date;
        private Long dayCostVnd;
        private List<ActivityDetail> activities;
    }

    @Getter
    @Builder
    public static class ActivityDetail {
        private Long id;
        private LocalTime startTime;
        private String name;
        private String description;
        private ActivityType type;
        private Long costVnd;
        private BigDecimal latitude;
        private BigDecimal longitude;
        private String imageUrl;
        private String bookingUrl;
        private Integer displayOrder;
        /** ID trong bảng place_cache — frontend dùng để gọi GET /places/{placeCacheId} */
        private Long placeCacheId;
        private String address;
    }

    @Getter
    @Builder
    public static class ChecklistItemDetail {
        private Long id;
        private String category;
        private String name;
        private Boolean isChecked;
        private Integer displayOrder;
    }
}
