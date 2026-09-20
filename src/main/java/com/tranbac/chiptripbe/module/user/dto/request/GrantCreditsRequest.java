package com.tranbac.chiptripbe.module.user.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class GrantCreditsRequest {

    @NotNull(message = "Số lượng credits không được trống")
    @Min(value = 1, message = "Số lượng credits phải lớn hơn 0")
    @Max(value = 21474836, message = "Số lượng credits vượt giới hạn cho phép")
    private Integer amount;
}
