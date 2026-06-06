package com.tranbac.chiptripbe.module.notification.event;

import java.time.LocalDate;

public record WeatherAlertEvent(
        Long recipientUserId,
        Long tripId,
        String tripTitle,
        LocalDate date,
        String condition,
        String description
) {}
