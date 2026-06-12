package com.tranbac.chiptripbe.module.notification.event;

/**
 * Phát khi có comment trên trip public.
 * reply=true: recipient là tác giả comment cha (bị trả lời).
 * reply=false: recipient là chủ trip.
 */
public record TripCommentedEvent(
        Long recipientUserId,
        Long tripId,
        String tripTitle,
        String commenterName,
        String previewBody,
        boolean reply
) {}
