package com.tranbac.chiptripbe.module.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank(message = "Nội dung không được để trống")
        @Size(max = 2000, message = "Nội dung không vượt quá 2000 ký tự")
        String content
) {}
