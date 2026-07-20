package com.tranbac.chiptripbe.module.place.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PhotoFailureReportRequest(
        @NotBlank(message = "URL anh khong duoc de trong")
        @Size(max = 2048, message = "URL anh qua dai")
        String url
) {
}
