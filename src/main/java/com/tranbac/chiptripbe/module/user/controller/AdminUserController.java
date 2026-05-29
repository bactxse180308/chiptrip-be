package com.tranbac.chiptripbe.module.user.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.response.PageMeta;
import com.tranbac.chiptripbe.module.user.dto.request.AdminUpdateUserRequest;
import com.tranbac.chiptripbe.module.user.dto.response.AdminUserDetailResponse;
import com.tranbac.chiptripbe.module.user.dto.response.UserResponse;
import com.tranbac.chiptripbe.module.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin — Users", description = "Quản trị danh sách người dùng")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;

    @Operation(summary = "Danh sách user (có phân trang, tìm kiếm, lọc)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserResponse>>> getAllUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<UserResponse> result = userService.getAllUsers(
                search, isActive, role,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        return ResponseEntity.ok(ApiResponse.ok(result.getContent(), PageMeta.of(result)));
    }

    @Operation(summary = "Chi tiết user (bao gồm trips, AI usage, sessions)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminUserDetailResponse>> getUserDetail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getAdminUserDetail(id)));
    }

    @Operation(summary = "Cập nhật user (fullName, aiCredits)")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody AdminUpdateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.adminUpdateUser(id, request)));
    }

    @Operation(summary = "Vô hiệu hoá tài khoản (soft delete)")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<Void>> deactivateUser(@PathVariable Long id) {
        userService.adminDeactivateUser(id);
        return ResponseEntity.ok(ApiResponse.noContent());
    }
}