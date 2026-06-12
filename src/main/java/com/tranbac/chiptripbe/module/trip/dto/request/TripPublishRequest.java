package com.tranbac.chiptripbe.module.trip.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class TripPublishRequest {

    /** true = publish, false = unpublish */
    @NotNull(message = "isPublic không được trống")
    private Boolean isPublic;
}
