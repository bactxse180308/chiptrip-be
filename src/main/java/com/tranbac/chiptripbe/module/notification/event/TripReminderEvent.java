package com.tranbac.chiptripbe.module.notification.event;

import java.time.LocalDate;

public record TripReminderEvent(Long recipientUserId, Long tripId, String tripTitle, LocalDate dateStart) {}
