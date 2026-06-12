package com.tranbac.chiptripbe.module.trip.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

@Getter
public class AddCommentRequest {

    @NotBlank(message = "Nội dung không được trống")
    @Size(max = 1000, message = "Nội dung tối đa 1000 ký tự")
    @Nationalized
    private String content;

    /** NULL = comment gốc, có giá trị = reply comment đó. */
    private Long parentId;
}
