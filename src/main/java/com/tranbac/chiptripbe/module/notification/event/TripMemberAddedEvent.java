package com.tranbac.chiptripbe.module.notification.event;

public record TripMemberAddedEvent(
        Long recipientUserId,
        Long tripId,
        String tripTitle,
        String inviterName
) {}
