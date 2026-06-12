package com.tranbac.chiptripbe.module.trip.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * Preview tối thiểu cho trang join — KHÔNG lộ ngày đi cụ thể / lịch trình chi tiết
 * (người có link nhưng chưa join chỉ cần biết mình được mời vào chuyến nào).
 */
@Getter
@Builder
public class TripInvitePreviewResponse {
    private Long tripId;
    private String title;
    private String destination;
    private String ownerName;
    private String ownerAvatarUrl;
    private long memberCount;
    /** Số ngày của chuyến (thay vì ngày cụ thể — quyền riêng tư). */
    private long numDays;
}
