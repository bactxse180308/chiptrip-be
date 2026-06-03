package com.tranbac.chiptripbe.module.user.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

@Getter
public class UpdateRoleRequest {

    @Nationalized
    @Size(max = 255)
    private String description;
}
