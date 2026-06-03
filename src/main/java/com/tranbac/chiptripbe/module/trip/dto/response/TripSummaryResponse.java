package com.tranbac.chiptripbe.module.trip.dto.response;

import lombok.Builder;
import lombok.Getter;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class TripSummaryResponse {
    private Long id;
    private Long userId;
    private String userFullName;
    private String userEmail;
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
}
