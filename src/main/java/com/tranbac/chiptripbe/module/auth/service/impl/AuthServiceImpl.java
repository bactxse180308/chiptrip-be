package com.tranbac.chiptripbe.module.auth.service.impl;

import com.tranbac.chiptripbe.common.enums.OAuthProvider;
import com.tranbac.chiptripbe.common.enums.RoleName;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.common.service.mail.EmailService;
import com.tranbac.chiptripbe.common.service.mail.MailProperties;
import com.tranbac.chiptripbe.common.security.GoogleTokenVerifier;
import com.tranbac.chiptripbe.common.security.JwtProvider;
import com.tranbac.chiptripbe.module.auth.dto.request.ForgotPasswordRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.LoginRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.RefreshTokenRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.RegisterRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.ResetPasswordRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.ResetPasswordWithOtpRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.SendOtpRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.VerifyOtpRequest;
import com.tranbac.chiptripbe.module.auth.dto.response.AuthResponse;
import com.tranbac.chiptripbe.module.auth.entity.EmailVerificationToken;
import com.tranbac.chiptripbe.module.auth.entity.OtpCode;
import com.tranbac.chiptripbe.module.auth.entity.PasswordResetToken;
import com.tranbac.chiptripbe.module.auth.entity.RefreshToken;
import com.tranbac.chiptripbe.module.auth.repository.EmailVerificationTokenRepository;
import com.tranbac.chiptripbe.module.auth.repository.OtpCodeRepository;
import com.tranbac.chiptripbe.module.auth.repository.PasswordResetTokenRepository;
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
import java.security.SecureRandom;
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
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final MailProperties mailProperties;
    private final GoogleTokenVerifier googleTokenVerifier;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.jwt.access-token-expiry-ms}")
    private long accessTokenExpiryMs;

    @Value("${app.jwt.refresh-token-expiry-ms}")
    private long refreshTokenExpiryMs;

    @Override
    @Transactional
    public void register(RegisterRequest request) {
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

        sendEmailVerificationOtp(user);
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

        if (!user.getEmailVerified()) {
            throw AppException.emailNotVerified();
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
        if (!user.getIsActive()) {
            throw AppException.forbidden("Tài khoản đã bị vô hiệu hóa");
        }
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

    @Override
    @Transactional
    public void sendOtp(SendOtpRequest request) {
        OtpCode.Purpose purpose;
        try {
            purpose = OtpCode.Purpose.valueOf(request.getPurpose().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AppException.badRequest("Loại OTP không hợp lệ");
        }

        if (purpose == OtpCode.Purpose.EMAIL_VERIFICATION) {
            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> AppException.notFound("Không tìm thấy tài khoản với email này"));
            if (user.getEmailVerified() && !user.isOAuthUser()) {
                throw AppException.conflict("Email đã được xác nhận trước đó");
            }
            createAndSendOtp(user.getEmail(), user.getFullName(), purpose);

        } else if (purpose == OtpCode.Purpose.PASSWORD_RESET) {
            userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
                createAndSendOtp(user.getEmail(), user.getFullName(), purpose);
            });
        }
    }

    @Override
    @Transactional
    public void verifyOtp(VerifyOtpRequest request) {
        OtpCode.Purpose purpose;
        try {
            purpose = OtpCode.Purpose.valueOf(request.getPurpose().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AppException.badRequest("Loại OTP không hợp lệ");
        }

        OtpCode otp = otpCodeRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(request.getEmail(), purpose)
                .orElseThrow(() -> AppException.badRequest("Mã OTP không tồn tại"));

        if (otp.getUsed()) {
            throw AppException.badRequest("Mã OTP đã được sử dụng");
        }
        if (otp.isExpired()) {
            throw AppException.badRequest("Mã OTP đã hết hạn");
        }
        if (otp.getAttempts() >= 5) {
            throw AppException.badRequest("Đã nhập sai quá nhiều lần. Vui lòng yêu cầu mã mới.");
        }

        if (!hashOtp(request.getOtp()).equals(otp.getOtpHash())) {
            otp.setAttempts(otp.getAttempts() + 1);
            otpCodeRepository.save(otp);
            int remaining = 5 - otp.getAttempts();
            throw AppException.badRequest("Mã OTP không đúng. Còn " + remaining + " lần thử.");
        }

        otp.setUsed(true);
        otpCodeRepository.save(otp);

        if (purpose == OtpCode.Purpose.EMAIL_VERIFICATION) {
            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> AppException.notFound("Không tìm thấy tài khoản"));
            user.setEmailVerified(true);
            userRepository.save(user);
            emailVerificationTokenRepository.invalidateAllByUserId(user.getId());
            log.info("Email verified via OTP for userId={}", user.getId());
        } else {
            log.info("OTP verified for purpose: {} email={}", purpose, "[REDACTED]");
        }
    }

    @Override
    @Transactional
    public void resetPasswordWithOtp(ResetPasswordWithOtpRequest request) {
        OtpCode otp = otpCodeRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(request.getEmail(), OtpCode.Purpose.PASSWORD_RESET)
                .orElseThrow(() -> AppException.badRequest("Mã OTP không tồn tại"));

        if (otp.getUsed()) {
            throw AppException.badRequest("Mã OTP đã được sử dụng");
        }
        if (otp.isExpired()) {
            throw AppException.badRequest("Mã OTP đã hết hạn");
        }
        if (otp.getAttempts() >= 5) {
            throw AppException.badRequest("Đã nhập sai quá nhiều lần. Vui lòng yêu cầu mã mới.");
        }

        if (!hashOtp(request.getOtp()).equals(otp.getOtpHash())) {
            otp.setAttempts(otp.getAttempts() + 1);
            otpCodeRepository.save(otp);
            int remaining = 5 - otp.getAttempts();
            throw AppException.badRequest("Mã OTP không đúng. Còn " + remaining + " lần thử.");
        }

        otp.setUsed(true);
        otpCodeRepository.save(otp);

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> AppException.notFound("Không tìm thấy tài khoản"));
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        List<RefreshToken> activeTokens = refreshTokenRepository.findAllByUserId(user.getId());
        activeTokens.forEach(t -> t.setRevoked(true));
        refreshTokenRepository.saveAll(activeTokens);

        log.info("Password reset via OTP for userId={}", user.getId());
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
            passwordResetTokenRepository.invalidateAllByUserId(user.getId());

            String token = UUID.randomUUID().toString();
            LocalDateTime expiry = LocalDateTime.now()
                    .plusHours(mailProperties.getResetPasswordExpiryHours());

            passwordResetTokenRepository.save(PasswordResetToken.builder()
                    .user(user)
                    .token(token)
                    .expiresAt(expiry)
                    .build());

            emailService.sendPasswordResetEmail(user.getEmail(), user.getFullName(), token);
            log.info("Password reset requested for userId={}", user.getId());
        });
        // Always return success to prevent email enumeration
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> AppException.badRequest("Token đặt lại mật khẩu không hợp lệ"));

        if (resetToken.getUsed()) {
            throw AppException.badRequest("Token đặt lại mật khẩu đã được sử dụng");
        }
        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw AppException.badRequest("Token đặt lại mật khẩu đã hết hạn");
        }

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Revoke all refresh tokens after password change
        List<RefreshToken> activeTokens = refreshTokenRepository.findAllByUserId(user.getId());
        activeTokens.forEach(t -> t.setRevoked(true));
        refreshTokenRepository.saveAll(activeTokens);

        log.info("Password reset completed for userId={}", user.getId());
    }

    @Override
    @Transactional
    public AuthResponse googleLogin(String idToken) {
        log.info("Google OAuth login attempt");

        GoogleTokenVerifier.GoogleUserInfo googleUser;
        try {
            googleUser = googleTokenVerifier.verify(idToken);
        } catch (Exception e) {
            log.warn("Google token verification failed: {}", e.getMessage());
            throw AppException.unauthorized("Xác thực Google thất bại");
        }

        User user = userRepository
                .findByOauthProviderAndOauthProviderId(OAuthProvider.GOOGLE, googleUser.sub())
                .or(() -> userRepository.findByEmail(googleUser.email())
                        .map(existingUser -> {
                            if (existingUser.getOauthProvider() != null
                                    && (!OAuthProvider.GOOGLE.equals(existingUser.getOauthProvider())
                                    || (existingUser.getOauthProviderId() != null
                                    && !existingUser.getOauthProviderId().equals(googleUser.sub())))) {
                                throw AppException.conflict("Email da duoc lien ket voi tai khoan OAuth khac");
                            }
                            log.info("Linking existing user to Google OAuth: userId={}", existingUser.getId());
                            existingUser.setOauthProvider(OAuthProvider.GOOGLE);
                            existingUser.setOauthProviderId(googleUser.sub());
                            existingUser.setEmailVerified(true);
                            if ((existingUser.getAvatarUrl() == null || existingUser.getAvatarUrl().isBlank())
                                    && googleUser.picture() != null && !googleUser.picture().isBlank()) {
                                existingUser.setAvatarUrl(googleUser.picture());
                            }
                            return userRepository.save(existingUser);
                        }))
                .orElseGet(() -> {
                    log.info("Creating new user via Google OAuth: email={}", googleUser.email());

                    Role userRole = roleRepository.findByName(RoleName.USER)
                            .orElseThrow(() -> AppException.notFound("Không tìm thấy role USER"));

                    User newUser = User.builder()
                            .email(googleUser.email())
                            .passwordHash(generateDisabledPasswordHash())
                            .fullName(googleUser.name() != null ? googleUser.name() : "User")
                            .avatarUrl(googleUser.picture() != null ? googleUser.picture() : null)
                            .role(userRole)
                            .oauthProvider(OAuthProvider.GOOGLE)
                            .oauthProviderId(googleUser.sub())
                            .emailVerified(true)
                            .build();
                    return userRepository.save(newUser);
                });

        if (!user.getIsActive()) {
            throw AppException.forbidden("Tài khoản đã bị vô hiệu hóa");
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        String rawRefreshToken = UUID.randomUUID().toString();
        saveRefreshToken(user, rawRefreshToken);

        log.info("Google OAuth login success: userId={}", user.getId());
        return buildAuthResponse(user, rawRefreshToken);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void sendEmailVerificationOtp(User user) {
        createAndSendOtp(user.getEmail(), user.getFullName(), OtpCode.Purpose.EMAIL_VERIFICATION);
    }

    private void createAndSendOtp(String email, String fullName, OtpCode.Purpose purpose) {
        otpCodeRepository.invalidateAllByEmailAndPurpose(email, purpose);

        String rawOtp = generateSecureOtp();
        String otpHash = hashOtp(rawOtp);
        LocalDateTime expiry = LocalDateTime.now().plusMinutes(mailProperties.getOtpExpiryMinutes());

        OtpCode otpCode = OtpCode.builder()
                .email(email)
                .purpose(purpose)
                .otpHash(otpHash)
                .expiresAt(expiry)
                .build();
        otpCodeRepository.save(otpCode);

        emailService.sendOtpEmail(email, fullName, rawOtp, mailProperties.getOtpExpiryMinutes(), purpose.name());
        log.info("OTP created and sent for purpose: {} to email: [REDACTED]", purpose);
    }

    private String generateSecureOtp() {
        int otp = 100000 + secureRandom.nextInt(900000);
        return String.valueOf(otp);
    }

    private String hashOtp(String rawOtp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(rawOtp.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

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
                .role(user.getRole().getName())
                .build();
    }

    private String generateDisabledPasswordHash() {
        return passwordEncoder.encode(UUID.randomUUID().toString());
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
