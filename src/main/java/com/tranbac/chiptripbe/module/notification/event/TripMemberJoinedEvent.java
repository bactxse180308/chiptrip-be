package com.tranbac.chiptripbe.module.notification.event;

/** Phát khi có người tự tham gia chuyến đi qua link mời → noti cho owner. */
public record TripMemberJoinedEvent(
        Long recipientUserId,
        Long tripId,
        String tripTitle,
        String joinerName
) {}
