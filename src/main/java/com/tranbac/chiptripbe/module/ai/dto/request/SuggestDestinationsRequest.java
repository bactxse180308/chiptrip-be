package com.tranbac.chiptripbe.module.ai.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SuggestDestinationsRequest {

    @NotNull
    @Size(min = 1, message = "Cần ít nhất 1 phong cách du lịch")
    private List<String> styles;

    @NotNull
    @Min(value = 0, message = "Ngân sách không hợp lệ")
    private Long budgetVnd;

    @NotNull
    @Min(value = 1, message = "Số ngày tối thiểu là 1")
    @Max(value = 30, message = "Số ngày tối đa là 30")
    private Integer days;
}
