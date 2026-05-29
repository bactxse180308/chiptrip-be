package com.tranbac.chiptripbe.module.trip.dto.request;

import com.tranbac.chiptripbe.common.enums.ChecklistCategory;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

@Getter
public class UpdateChecklistItemRequest {

    @Nationalized
    private String name;

    private ChecklistCategory category;
}
