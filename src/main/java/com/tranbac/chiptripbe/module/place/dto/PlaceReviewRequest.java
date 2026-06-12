package com.tranbac.chiptripbe.module.place.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

@Getter
public class PlaceReviewRequest {

    @NotNull(message = "Rating không được trống")
    @Min(value = 1, message = "Rating tối thiểu là 1")
    @Max(value = 5, message = "Rating tối đa là 5")
    private Integer rating;

    @Size(max = 500, message = "Nội dung tối đa 500 ký tự")
    @Nationalized
    private String content;
}
