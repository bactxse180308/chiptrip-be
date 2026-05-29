package com.tranbac.chiptripbe.module.user.service;

import com.tranbac.chiptripbe.module.user.dto.request.AdminUpdateUserRequest;
import com.tranbac.chiptripbe.module.user.dto.request.UpdateProfileRequest;
import com.tranbac.chiptripbe.module.user.dto.response.AdminUserDetailResponse;
import com.tranbac.chiptripbe.module.user.dto.response.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

    UserResponse getMyProfile(Long userId);

    UserResponse updateProfile(Long userId, UpdateProfileRequest request);

    Page<UserResponse> getAllUsers(String search, Boolean isActive, String role, Pageable pageable);

    AdminUserDetailResponse getAdminUserDetail(Long userId);

    UserResponse adminUpdateUser(Long userId, AdminUpdateUserRequest request);

    void adminDeactivateUser(Long userId);
}