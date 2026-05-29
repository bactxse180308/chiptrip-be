package com.tranbac.chiptripbe.module.trip.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

import java.time.LocalDate;

@Getter
public class GenerateTripRequest {

    @NotBlank(message = "Điểm đi không được trống")
    @Nationalized
    private String departure;

    @NotBlank(message = "Điểm đến không được trống")
    @Nationalized
    private String destination;

    @NotNull(message = "Ngày bắt đầu không được trống")
    private LocalDate startDate;

    @NotNull(message = "Ngày kết thúc không được trống")
    private LocalDate endDate;

    @NotNull(message = "Số người không được trống")
    @Min(value = 1, message = "Số người tối thiểu là 1")
    private Integer peopleCount;

    @NotNull(message = "Ngân sách không được trống")
    @Min(value = 0, message = "Ngân sách không được âm")
    private Long budgetVnd;

    private java.util.List<String> styles;
}
