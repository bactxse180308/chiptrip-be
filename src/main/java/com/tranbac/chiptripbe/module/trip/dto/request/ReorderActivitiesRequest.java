package com.tranbac.chiptripbe.module.trip.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.util.List;

@Getter
public class ReorderActivitiesRequest {

    @NotEmpty(message = "Danh sách ID không được trống")
    private List<Long> orderedIds;
}
