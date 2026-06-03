package com.tranbac.chiptripbe.module.trip.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

import java.time.LocalDate;

@Getter
public class UpdateTripRequest {

    @Nationalized
    private String title;

    private LocalDate dateStart;

    private LocalDate dateEnd;

    @NotNull(message = "Số người không được trống")
    @jakarta.validation.constraints.Min(value = 1, message = "Số người tối thiểu là 1")
    private Integer peopleCount;

    @jakarta.validation.constraints.Min(value = 0, message = "Ngân sách không được âm")
    private Long budgetVnd;

    private java.util.List<String> styles;
}
