package com.tranbac.chiptripbe.module.user.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class ToggleUserStatusRequest {

    @NotNull(message = "Trạng thái không được trống")
    private Boolean enabled;
}
