package com.tranbac.chiptripbe.module.user.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.common.service.mail.EmailService;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.auth.entity.EmailVerificationToken;
import com.tranbac.chiptripbe.module.auth.entity.RefreshToken;
import com.tranbac.chiptripbe.module.auth.repository.EmailVerificationTokenRepository;
import com.tranbac.chiptripbe.module.auth.repository.RefreshTokenRepository;
import com.tranbac.chiptripbe.module.payment.entity.OrderStatus;
import com.tranbac.chiptripbe.module.payment.entity.PaymentOrder;
import com.tranbac.chiptripbe.module.payment.repository.PaymentOrderRepository;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.user.dto.request.*;
import com.tranbac.chiptripbe.module.user.dto.response.AdminUserDetailResponse;
import com.tranbac.chiptripbe.module.user.dto.response.RoleResponse;
import com.tranbac.chiptripbe.module.user.dto.response.UserResponse;
import com.tranbac.chiptripbe.module.user.entity.Role;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.RoleRepository;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import com.tranbac.chiptripbe.module.user.service.UserService;
import com.tranbac.chiptripbe.module.user.specification.UserSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TripRepository tripRepository;
    private final AiUsageRepository aiUsageRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Value("${app.mail.verification-expiry-hours:24}")
    private int verificationExpiryHours;

    @Override
    public UserResponse getMyProfile(Long userId) {
        return toResponse(findActive(userId));
    }

    @Override
    public List<UserResponse> searchUsers(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        Specification<User> spec = Specification.where(UserSpecification.withIsActive(true))
                .and(UserSpecification.withSearch(query));
        Page<User> usersPage = userRepository.findAll(spec, org.springframework.data.domain.PageRequest.of(0, 10));
        return usersPage.getContent().stream().map(this::toResponse).toList();
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
        if (request.getPreferences() != null) {
            user.setPreferences(request.getPreferences());
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

        List<Object[]> aiStatsRows = aiUsageRepository.aggregateByUserId(userId);
        Object[] aiStats = aiStatsRows.isEmpty() ? new Object[0] : aiStatsRows.get(0);
        AdminUserDetailResponse.AiUsageSummary aiSummary = AdminUserDetailResponse.AiUsageSummary.builder()
                .totalCount(numberAt(aiStats, 0))
                .totalTokensIn(numberAt(aiStats, 1))
                .totalTokensOut(numberAt(aiStats, 2))
                .totalCostUsd(decimalAt(aiStats, 3))
                .build();

        long activeSessions = refreshTokenRepository.countByUserIdAndRevokedFalse(userId);

        AdminUserDetailResponse.PaymentSummary paymentSummary = buildPaymentSummary(userId);

        return AdminUserDetailResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .avatarUrl(user.getAvatarUrl())
                .aiCredits(user.getAiCredits())
                .aiCreditUnits(user.effectiveAiCreditUnits())
                .aiCreditBalance(user.aiCreditBalance())
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
                .payment(paymentSummary)
                .build();
    }

    private AdminUserDetailResponse.PaymentSummary buildPaymentSummary(Long userId) {
        List<PaymentOrder> orders = paymentOrderRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<PaymentOrder> paidOrders = orders.stream()
                .filter(o -> o.getStatus() == OrderStatus.PAID)
                .toList();

        return AdminUserDetailResponse.PaymentSummary.builder()
                .premium(!paidOrders.isEmpty())
                .paidOrderCount(paidOrders.size())
                .totalSpentVnd(paidOrders.stream().mapToLong(PaymentOrder::getAmountVnd).sum())
                .totalCreditsPurchased(paidOrders.stream()
                        .mapToLong(o -> o.getCredits() == null ? 0L : o.getCredits()).sum())
                .lastPaidAt(paidOrders.stream()
                        .map(PaymentOrder::getPaidAt)
                        .filter(java.util.Objects::nonNull)
                        .max(LocalDateTime::compareTo)
                        .orElse(null))
                .orders(orders.stream().map(o -> AdminUserDetailResponse.OrderItem.builder()
                        .id(o.getId())
                        .orderCode(o.getOrderCode())
                        .planCode(o.getPlanCode())
                        .amountVnd(o.getAmountVnd())
                        .credits(o.getCredits())
                        .status(o.getStatus().name())
                        .createdAt(o.getCreatedAt())
                        .paidAt(o.getPaidAt())
                        .build()).toList())
                .build();
    }

    private long numberAt(Object[] row, int index) {
        Object value = aggregateValueAt(row, index);
        return value == null ? 0L : ((Number) value).longValue();
    }

    private BigDecimal decimalAt(Object[] row, int index) {
        Object value = aggregateValueAt(row, index);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private Object aggregateValueAt(Object[] row, int index) {
        Object[] values = unwrapAggregateRow(row);
        return values.length > index ? values[index] : null;
    }

    private Object[] unwrapAggregateRow(Object[] row) {
        if (row == null || row.length == 0) return new Object[0];
        if (row.length == 1 && row[0] instanceof Object[] nested) return nested;
        return row;
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
            user.setWholeAiCredits(request.getAiCredits());
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

    @Override
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = findActive(userId);
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw AppException.badRequest("Mật khẩu cũ không đúng");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Password changed for userId={}", userId);
    }

    @Override
    @Transactional
    public void resendVerification(Long userId) {
        User user = findActive(userId);
        if (user.getEmailVerified()) {
            throw AppException.badRequest("Email đã được xác nhận");
        }
        emailVerificationTokenRepository.invalidateAllByUserId(userId);
        String token = UUID.randomUUID().toString();
        LocalDateTime expiry = LocalDateTime.now().plusHours(verificationExpiryHours);
        emailVerificationTokenRepository.save(EmailVerificationToken.builder()
                .user(user).token(token).expiresAt(expiry).used(false).build());
        emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), token);
        log.info("Resent verification email for userId={}", userId);
    }

    @Override
    @Transactional
    public void adminActivateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));
        if (user.getIsActive()) {
            throw AppException.badRequest("Tài khoản đã được kích hoạt");
        }
        user.setIsActive(true);
        userRepository.save(user);
        log.info("Admin activated userId={}", userId);
    }

    @Override
    @Transactional
    public void adminToggleStatus(Long userId, boolean enabled) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));
        user.setIsActive(enabled);
        userRepository.save(user);
        if (!enabled) {
            List<RefreshToken> tokens = refreshTokenRepository.findAllByUserId(userId);
            tokens.forEach(t -> t.setRevoked(true));
            refreshTokenRepository.saveAll(tokens);
        }
        log.info("Admin toggled userId={} enabled={}", userId, enabled);
    }

    @Override
    @Transactional
    public UserResponse adminGrantCredits(Long userId, GrantCreditsRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));
        user.addWholeAiCredits(request.getAmount());
        userRepository.save(user);
        log.info("Admin granted {} credits to userId={}", request.getAmount(), userId);
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse adminAssignRole(Long userId, AssignRoleRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));
        Role role = roleRepository.findByName(request.getRoleName())
                .orElseThrow(() -> AppException.notFound("Không tìm thấy role: " + request.getRoleName()));
        user.setRole(role);
        userRepository.save(user);
        log.info("Admin assigned role {} to userId={}", request.getRoleName(), userId);
        return toResponse(user);
    }

    @Override
    @Transactional
    public void adminRemoveRole(Long userId, Long roleId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy role"));
        if (role.getName().equals(com.tranbac.chiptripbe.common.enums.RoleName.ADMIN)) {
            throw AppException.badRequest("Không thể gỡ vai trò ADMIN");
        }
        user.setRole(roleRepository.findByName(com.tranbac.chiptripbe.common.enums.RoleName.USER)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy role USER mặc định")));
        userRepository.save(user);
        log.info("Admin removed role {} from userId={}", roleId, userId);
    }

    @Override
    @Transactional
    public void deleteMyAccount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));
        user.setIsActive(false);
        userRepository.save(user);
        List<RefreshToken> tokens = refreshTokenRepository.findAllByUserId(userId);
        tokens.forEach(t -> t.setRevoked(true));
        refreshTokenRepository.saveAll(tokens);
        log.info("User deleted own account userId={}", userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RoleResponse> getAllRoles(Pageable pageable) {
        return roleRepository.findAll(pageable).map(this::toRoleResponse);
    }

    @Override
    @Transactional
    public RoleResponse createRole(CreateRoleRequest request) {
        if (roleRepository.findByName(request.getName()).isPresent()) {
            throw AppException.conflict("Role đã tồn tại");
        }
        Role role = Role.builder().name(request.getName()).description(request.getDescription()).build();
        role = roleRepository.save(role);
        log.info("Created role id={} name={}", role.getId(), role.getName());
        return toRoleResponse(role);
    }

    @Override
    @Transactional
    public RoleResponse updateRole(Long roleId, UpdateRoleRequest request) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy role"));
        if (request.getDescription() != null) {
            role.setDescription(request.getDescription());
        }
        role = roleRepository.save(role);
        log.info("Updated role id={}", roleId);
        return toRoleResponse(role);
    }

    @Override
    @Transactional
    public void deleteRole(Long roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy role"));
        if (role.getName().equals(com.tranbac.chiptripbe.common.enums.RoleName.USER) ||
                role.getName().equals(com.tranbac.chiptripbe.common.enums.RoleName.ADMIN)) {
            throw AppException.badRequest("Không thể xoá role mặc định");
        }
        roleRepository.delete(role);
        log.info("Deleted role id={}", roleId);
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
                .aiCreditUnits(user.effectiveAiCreditUnits())
                .aiCreditBalance(user.aiCreditBalance())
                .isPremium(user.isPremium())
                .trialCreditBalance(user.getTrialCreditBalance())
                .isActive(user.getIsActive())
                .emailVerified(user.getEmailVerified())
                .role(user.getRole().getName())
                .preferences(user.getPreferences())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    private RoleResponse toRoleResponse(Role role) {
        return RoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .createdAt(role.getCreatedAt())
                .build();
    }
}
