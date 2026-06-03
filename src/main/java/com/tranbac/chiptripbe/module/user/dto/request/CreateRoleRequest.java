package com.tranbac.chiptripbe.module.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

@Getter
public class CreateRoleRequest {

    @NotBlank(message = "Tên role không được trống")
    @Size(max = 50)
    private String name;

    @Nationalized
    @Size(max = 255)
    private String description;
}
