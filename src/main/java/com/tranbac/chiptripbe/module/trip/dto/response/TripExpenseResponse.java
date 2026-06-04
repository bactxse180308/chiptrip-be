package com.tranbac.chiptripbe.module.trip.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class TripExpenseResponse {

    private Long id;

    @JsonProperty("trip_id")
    private Long tripId;

    @JsonProperty("paid_by")
    private String paidBy;

    private String title;

    private Long amount;

    private String category;

    @JsonProperty("split_among")
    private List<String> splitAmong;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
