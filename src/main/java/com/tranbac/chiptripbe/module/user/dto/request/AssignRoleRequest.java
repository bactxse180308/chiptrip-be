package com.tranbac.chiptripbe.module.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class AssignRoleRequest {

    @NotBlank(message = "Tên role không được trống")
    private String roleName;
}
