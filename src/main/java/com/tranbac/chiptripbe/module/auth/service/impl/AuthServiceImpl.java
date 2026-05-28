package com.tranbac.chiptripbe.module.auth.service.impl;

import com.tranbac.chiptripbe.common.enums.RoleName;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.common.security.JwtProvider;
import com.tranbac.chiptripbe.module.auth.dto.request.LoginRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.RefreshTokenRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.RegisterRequest;
import com.tranbac.chiptripbe.module.auth.dto.response.AuthResponse;
import com.tranbac.chiptripbe.module.auth.entity.RefreshToken;
import com.tranbac.chiptripbe.module.auth.repository.RefreshTokenRepository;
import com.tranbac.chiptripbe.module.auth.service.AuthService;
import com.tranbac.chiptripbe.module.user.entity.Role;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.RoleRepository;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.jwt.access-token-expiry-ms}")
    private long accessTokenExpiryMs;

    @Value("${app.jwt.refresh-token-expiry-ms}")
    private long refreshTokenExpiryMs;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        log.info("Register attempt: email=[REDACTED]");

        if (userRepository.existsByEmail(request.getEmail())) {
            throw AppException.conflict("Email đã được sử dụng");
        }

        Role userRole = roleRepository.findByName(RoleName.USER)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy role USER"));

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(userRole)
                .build();

        userRepository.save(user);
        log.info("New user registered: id={}", user.getId());

        String rawRefreshToken = UUID.randomUUID().toString();
        saveRefreshToken(user, rawRefreshToken);

        return buildAuthResponse(user, rawRefreshToken);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        log.info("Login attempt: email=[REDACTED]");

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> AppException.unauthorized("Email hoặc mật khẩu không đúng"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw AppException.unauthorized("Email hoặc mật khẩu không đúng");
        }

        if (!user.getIsActive()) {
            throw AppException.forbidden("Tài khoản đã bị vô hiệu hóa");
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        String rawRefreshToken = UUID.randomUUID().toString();
        saveRefreshToken(user, rawRefreshToken);

        log.info("User logged in: id={}", user.getId());
        return buildAuthResponse(user, rawRefreshToken);
    }

    @Override
    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String tokenHash = hash(request.getRefreshToken());

        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> AppException.unauthorized("Refresh token không hợp lệ"));

        if (stored.getRevoked() || stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw AppException.unauthorized("Refresh token đã hết hạn hoặc bị thu hồi");
        }

        // Token rotation — revoke old, issue new
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        User user = stored.getUser();
        String newRawToken = UUID.randomUUID().toString();
        saveRefreshToken(user, newRawToken);

        log.info("Token refreshed for userId={}", user.getId());
        return buildAuthResponse(user, newRawToken);
    }

    @Override
    @Transactional
    public void logout(Long userId) {
        List<RefreshToken> activeTokens = refreshTokenRepository.findAllByUserId(userId);
        activeTokens.forEach(token -> token.setRevoked(true));
        refreshTokenRepository.saveAll(activeTokens);
        log.info("Logout userId={} — revoked {} token(s)", userId, activeTokens.size());
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void saveRefreshToken(User user, String rawToken) {
        LocalDateTime expiry = LocalDateTime.now().plusSeconds(refreshTokenExpiryMs / 1000);
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .expiresAt(expiry)
                .build();
        refreshTokenRepository.save(token);
    }

    private AuthResponse buildAuthResponse(User user, String rawRefreshToken) {
        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().getName());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn(accessTokenExpiryMs / 1000)
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .build();
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}