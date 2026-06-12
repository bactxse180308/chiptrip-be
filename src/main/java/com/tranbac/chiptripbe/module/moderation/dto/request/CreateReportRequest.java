package com.tranbac.chiptripbe.module.moderation.dto.request;

import com.tranbac.chiptripbe.module.moderation.enums.ReportTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

@Getter
public class CreateReportRequest {

    @NotNull(message = "Loại nội dung không được trống")
    private ReportTargetType targetType;

    @NotNull(message = "targetId không được trống")
    private Long targetId;

    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    @Nationalized
    private String reason;
}
