package com.tranbac.chiptripbe.module.user.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.response.PageMeta;
import com.tranbac.chiptripbe.module.user.dto.request.AssignRoleRequest;
import com.tranbac.chiptripbe.module.user.dto.request.CreateRoleRequest;
import com.tranbac.chiptripbe.module.user.dto.request.GrantCreditsRequest;
import com.tranbac.chiptripbe.module.user.dto.request.UpdateRoleRequest;
import com.tranbac.chiptripbe.module.user.dto.response.RoleResponse;
import com.tranbac.chiptripbe.module.user.dto.response.UserResponse;
import com.tranbac.chiptripbe.module.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin — Roles", description = "Quản trị vai trò")
@RestController
@RequestMapping("/api/v1/admin/roles")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRoleController {

    private final UserService userService;

    @Operation(summary = "Danh sách vai trò")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<List<RoleResponse>>> getAllRoles() {
        Page<RoleResponse> result = userService.getAllRoles(PageRequest.of(0, 100));
        return ResponseEntity.ok(ApiResponse.ok(result.getContent()));
    }

    @Operation(summary = "Tạo vai trò mới")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<RoleResponse>> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(userService.createRole(request)));
    }

    @Operation(summary = "Cập nhật vai trò")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<RoleResponse>> updateRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRoleRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.updateRole(id, request)));
    }

    @Operation(summary = "Xoá vai trò")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<ApiResponse<Void>> deleteRole(@PathVariable Long id) {
        userService.deleteRole(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }
}
