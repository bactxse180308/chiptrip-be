package com.tranbac.chiptripbe.module.user.service;

import com.tranbac.chiptripbe.module.user.dto.request.*;
import com.tranbac.chiptripbe.module.user.dto.response.AdminUserDetailResponse;
import com.tranbac.chiptripbe.module.user.dto.response.RoleResponse;
import com.tranbac.chiptripbe.module.user.dto.response.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface UserService {

    UserResponse getMyProfile(Long userId);

    List<UserResponse> searchUsers(String query);

    UserResponse updateProfile(Long userId, UpdateProfileRequest request);

    void changePassword(Long userId, ChangePasswordRequest request);

    void resendVerification(Long userId);

    Page<UserResponse> getAllUsers(String search, Boolean isActive, String role, Pageable pageable);

    AdminUserDetailResponse getAdminUserDetail(Long userId);

    UserResponse adminUpdateUser(Long userId, AdminUpdateUserRequest request);

    void adminDeactivateUser(Long userId);

    void adminActivateUser(Long userId);

    void adminToggleStatus(Long userId, boolean enabled);

    UserResponse adminGrantCredits(Long userId, GrantCreditsRequest request);

    UserResponse adminAssignRole(Long userId, AssignRoleRequest request);

    void adminRemoveRole(Long userId, Long roleId);

    void deleteMyAccount(Long userId);

    Page<RoleResponse> getAllRoles(Pageable pageable);

    RoleResponse createRole(CreateRoleRequest request);

    RoleResponse updateRole(Long roleId, UpdateRoleRequest request);

    void deleteRole(Long roleId);
}
