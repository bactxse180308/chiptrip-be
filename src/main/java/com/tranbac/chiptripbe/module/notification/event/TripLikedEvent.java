package com.tranbac.chiptripbe.module.notification.event;

/** Phát khi user thả tim trip public (chỉ khi like, không phát khi unlike). */
public record TripLikedEvent(
        Long recipientUserId,
        Long tripId,
        String tripTitle,
        String likerName
) {}
