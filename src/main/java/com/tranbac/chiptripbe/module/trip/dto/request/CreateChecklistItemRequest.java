package com.tranbac.chiptripbe.module.trip.dto.request;

import com.tranbac.chiptripbe.common.enums.ChecklistCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

@Getter
public class CreateChecklistItemRequest {

    @NotNull(message = "Danh mục không được trống")
    private ChecklistCategory category;

    @NotBlank(message = "Tên item không được trống")
    @Nationalized
    private String name;
}
