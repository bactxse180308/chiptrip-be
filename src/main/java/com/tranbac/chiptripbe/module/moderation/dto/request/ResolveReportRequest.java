package com.tranbac.chiptripbe.module.moderation.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class ResolveReportRequest {

    public enum Action {
        DELETE_CONTENT,  // xóa comment / gỡ public trip + đánh dấu RESOLVED
        DISMISS          // bỏ qua, nội dung không vi phạm
    }

    @NotNull(message = "Hành động không được trống")
    private Action action;
}
