package com.tranbac.chiptripbe.module.user.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.user.dto.request.ChangePasswordRequest;
import com.tranbac.chiptripbe.module.user.dto.request.UpdateProfileRequest;
import com.tranbac.chiptripbe.module.user.dto.response.UserResponse;
import com.tranbac.chiptripbe.module.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Users", description = "Quản lý hồ sơ người dùng")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "Lấy hồ sơ bản thân")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getMyProfile(principal.getId())));
    }

    @Operation(summary = "Cập nhật hồ sơ (fullName, avatarUrl)")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.updateProfile(principal.getId(), request)));
    }

    @Operation(summary = "Đổi mật khẩu")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(null, "Đổi mật khẩu thành công"));
    }

    @Operation(summary = "Gửi lại email xác nhận")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/resend-verification")
    public ResponseEntity<ApiResponse<Void>> resendVerification(
            @AuthenticationPrincipal UserPrincipal principal) {
        userService.resendVerification(principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(null, "Đã gửi email xác nhận"));
    }

    @Operation(summary = "Vô hiệu hoá tài khoản (soft delete)")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> deleteMyAccount(
            @AuthenticationPrincipal UserPrincipal principal) {
        userService.deleteMyAccount(principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(null, "Tài khoản đã được vô hiệu hoá"));
    }
}