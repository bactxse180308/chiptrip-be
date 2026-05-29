package com.tranbac.chiptripbe.module.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class ChangePasswordRequest {

    @NotBlank(message = "Mật khẩu cũ không được trống")
    private String oldPassword;

    @NotBlank(message = "Mật khẩu mới không được trống")
    @Size(min = 6, max = 128, message = "Mật khẩu phải từ 6 đến 128 ký tự")
    private String newPassword;
}
