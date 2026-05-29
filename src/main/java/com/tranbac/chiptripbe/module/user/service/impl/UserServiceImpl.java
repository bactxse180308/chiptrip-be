package com.tranbac.chiptripbe.module.user.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.auth.entity.RefreshToken;
import com.tranbac.chiptripbe.module.auth.repository.RefreshTokenRepository;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.user.dto.request.AdminUpdateUserRequest;
import com.tranbac.chiptripbe.module.user.dto.request.UpdateProfileRequest;
import com.tranbac.chiptripbe.module.user.dto.response.AdminUserDetailResponse;
import com.tranbac.chiptripbe.module.user.dto.response.UserResponse;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import com.tranbac.chiptripbe.module.user.service.UserService;
import com.tranbac.chiptripbe.module.user.specification.UserSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TripRepository tripRepository;
    private final AiUsageRepository aiUsageRepository;

    @Override
    public UserResponse getMyProfile(Long userId) {
        return toResponse(findActive(userId));
    }

    @Override
    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = findActive(userId);
        if (StringUtils.hasText(request.getFullName())) {
            user.setFullName(request.getFullName());
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(request.getAvatarUrl());
        }
        log.info("Profile updated for userId={}", userId);
        return toResponse(userRepository.save(user));
    }

    @Override
    public Page<UserResponse> getAllUsers(String search, Boolean isActive, String role, Pageable pageable) {
        Specification<User> spec = Specification.allOf();
        if (StringUtils.hasText(search)) {
            spec = spec.and(UserSpecification.withSearch(search));
        }
        if (isActive != null) {
            spec = spec.and(UserSpecification.withIsActive(isActive));
        }
        if (StringUtils.hasText(role)) {
            spec = spec.and(UserSpecification.withRole(role));
        }
        return userRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Override
    public AdminUserDetailResponse getAdminUserDetail(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));

        List<Trip> trips = tripRepository.findByUserId(userId, Pageable.unpaged()).getContent();

        Object[] aiStats = aiUsageRepository.aggregateByUserId(userId);
        AdminUserDetailResponse.AiUsageSummary aiSummary = AdminUserDetailResponse.AiUsageSummary.builder()
                .totalCount(((Number) aiStats[0]).longValue())
                .totalTokensIn(((Number) aiStats[1]).longValue())
                .totalTokensOut(((Number) aiStats[2]).longValue())
                .totalCostUsd((BigDecimal) aiStats[3])
                .build();

        long activeSessions = refreshTokenRepository.countByUserIdAndRevokedFalse(userId);

        return AdminUserDetailResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .avatarUrl(user.getAvatarUrl())
                .aiCredits(user.getAiCredits())
                .isActive(user.getIsActive())
                .emailVerified(user.getEmailVerified())
                .role(user.getRole().getName())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .trips(trips.stream().map(t -> AdminUserDetailResponse.TripSummary.builder()
                        .id(t.getId())
                        .title(t.getTitle())
                        .departure(t.getDeparture())
                        .destination(t.getDestination())
                        .dateStart(t.getDateStart())
                        .dateEnd(t.getDateEnd())
                        .peopleCount(t.getPeopleCount())
                        .budgetVnd(t.getBudgetVnd())
                        .styles(t.getStyles())
                        .createdAt(t.getCreatedAt())
                        .build()).toList())
                .aiUsage(aiSummary)
                .activeSessionCount(activeSessions)
                .build();
    }

    @Override
    @Transactional
    public UserResponse adminUpdateUser(Long userId, AdminUpdateUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));

        if (StringUtils.hasText(request.getFullName())) {
            user.setFullName(request.getFullName());
        }
        if (request.getAiCredits() != null) {
            user.setAiCredits(request.getAiCredits());
        }
        log.info("Admin updated userId={}", userId);
        return toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public void adminDeactivateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));

        if (!user.getIsActive()) {
            throw AppException.badRequest("Tài khoản đã bị vô hiệu hóa");
        }

        user.setIsActive(false);
        userRepository.save(user);

        List<RefreshToken> tokens = refreshTokenRepository.findAllByUserId(userId);
        tokens.forEach(t -> t.setRevoked(true));
        refreshTokenRepository.saveAll(tokens);

        log.info("Admin deactivated userId={}", userId);
    }

    private User findActive(Long userId) {
        return userRepository.findById(userId)
                .filter(User::getIsActive)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .avatarUrl(user.getAvatarUrl())
                .aiCredits(user.getAiCredits())
                .isActive(user.getIsActive())
                .emailVerified(user.getEmailVerified())
                .role(user.getRole().getName())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}