package com.tranbac.chiptripbe.module.notification.event;

/** Phát ngày sau khi chuyến kết thúc (dateEnd + 1) → nhắc owner đánh giá địa điểm & chia sẻ lịch trình. */
public record PostTripEvent(
        Long recipientUserId,
        Long tripId,
        String tripTitle
) {}
