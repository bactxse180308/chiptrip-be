package com.tranbac.chiptripbe.module.auth.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.common.security.*;
import com.tranbac.chiptripbe.common.service.mail.*;
import com.tranbac.chiptripbe.module.auth.dto.request.*;
import com.tranbac.chiptripbe.module.auth.entity.OtpCode;
import com.tranbac.chiptripbe.module.auth.repository.*;
import com.tranbac.chiptripbe.module.auth.service.AuthService;
import com.tranbac.chiptripbe.module.user.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(AuthOtpTransactionTest.Config.class)
@TestPropertySource(properties = {"app.jwt.access-token-expiry-ms=900000", "app.jwt.refresh-token-expiry-ms=86400000"})
class AuthOtpTransactionTest {
    @Autowired AuthService auth;
    @Autowired OtpCodeRepository otps;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refresh;
    @Autowired PasswordEncoder encoder;

    @Test void resetPasswordWrongAttemptCommitsWithoutChangingPassword() throws Exception {
        var user = seedUser();
        var otp = seedResetOtp(user.getEmail());
        assertThrows(AppException.class, () -> auth.resetPasswordWithOtp(resetRequest(user.getEmail(), "000000")));
        assertEquals(1, otps.findById(otp.getId()).orElseThrow().getAttempts());
        assertEquals("old-hash", users.findById(user.getId()).orElseThrow().getPasswordHash());
    }

    @Test void successfulResetConsumesOtpAndRevokesRefreshTogether() throws Exception {
        var user = seedUser();
        var otp = seedResetOtp(user.getEmail());
        var token = refresh.save(com.tranbac.chiptripbe.module.auth.entity.RefreshToken.builder()
                .user(user).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        when(encoder.encode("new-password")).thenReturn("new-hash");
        var request = resetRequest(user.getEmail(), "123456");
        auth.resetPasswordWithOtp(request);
        assertTrue(otps.findById(otp.getId()).orElseThrow().getUsed());
        assertEquals("new-hash", users.findById(user.getId()).orElseThrow().getPasswordHash());
        assertTrue(refresh.findById(token.getId()).orElseThrow().getRevoked());
        assertThrows(AppException.class, () -> auth.resetPasswordWithOtp(request));
    }

    @Test void resetFailureRollsBackOtpConsumption() throws Exception {
        var user = seedUser();
        var otp = seedResetOtp(user.getEmail());
        when(encoder.encode("new-password")).thenThrow(new IllegalStateException("encoder unavailable"));
        try {
            assertThrows(IllegalStateException.class, () -> auth.resetPasswordWithOtp(resetRequest(user.getEmail(), "123456")));
            assertFalse(otps.findById(otp.getId()).orElseThrow().getUsed());
            assertEquals("old-hash", users.findById(user.getId()).orElseThrow().getPasswordHash());
        } finally { reset(encoder); }
    }

    @Test void concurrentWrongAttemptsCannotExceedBudget() throws Exception {
        var user = seedUser();
        var otp = seedResetOtp(user.getEmail());
        var request = resetRequest(user.getEmail(), "000000");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var ready = new java.util.concurrent.CountDownLatch(8);
            var start = new java.util.concurrent.CountDownLatch(1);
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) jobs.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(AppException.class, () -> auth.resetPasswordWithOtp(request));
                return null;
            }));
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (var job : jobs) job.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(5, otps.findById(otp.getId()).orElseThrow().getAttempts());
        assertFalse(otps.findById(otp.getId()).orElseThrow().getUsed());
    }

    @Test void concurrentCorrectOtpIsConsumedOnlyOnce() throws Exception {
        var user = seedUser();
        var otp = seedResetOtp(user.getEmail());
        when(encoder.encode("new-password")).thenReturn("new-hash");
        var request = resetRequest(user.getEmail(), "123456");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> consume = () -> {
                ready.countDown();
                assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                try { auth.resetPasswordWithOtp(request); return true; }
                catch (AppException expected) { return false; }
            };
            var first = pool.submit(consume);
            var second = pool.submit(consume);
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            boolean firstSucceeded = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(firstSucceeded, secondSucceeded);
        }
        assertTrue(otps.findById(otp.getId()).orElseThrow().getUsed());
        assertEquals("new-hash", users.findById(user.getId()).orElseThrow().getPasswordHash());
    }

    @Test void expiredOtpCannotChangePasswordOrAttempts() throws Exception {
        var user = seedUser();
        var otp = seedResetOtp(user.getEmail());
        otp.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        otps.save(otp);
        assertThrows(AppException.class, () -> auth.resetPasswordWithOtp(resetRequest(user.getEmail(), "123456")));
        var actual = otps.findById(otp.getId()).orElseThrow();
        assertEquals(0, actual.getAttempts());
        assertFalse(actual.getUsed());
        assertEquals("old-hash", users.findById(user.getId()).orElseThrow().getPasswordHash());
    }

    private com.tranbac.chiptripbe.module.user.entity.User seedUser() {
        var user = com.tranbac.chiptripbe.module.user.entity.User.builder()
                .email(UUID.randomUUID() + "@example.com").fullName("Test User").passwordHash("old-hash")
                .oauthProvider(com.tranbac.chiptripbe.common.enums.OAuthProvider.GOOGLE).oauthProviderId(UUID.randomUUID().toString())
                .role(com.tranbac.chiptripbe.module.user.entity.Role.builder().name("R_" + UUID.randomUUID()).build()).build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return users.save(user);
    }

    private OtpCode seedResetOtp(String email) throws Exception {
        return otps.save(OtpCode.builder().email(email).purpose(OtpCode.Purpose.PASSWORD_RESET)
                .otpHash(hash("123456")).expiresAt(LocalDateTime.now().plusMinutes(5)).build());
    }

    private ResetPasswordWithOtpRequest resetRequest(String email, String otp) {
        var request = new ResetPasswordWithOtpRequest();
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "otp", otp);
        ReflectionTestUtils.setField(request, "newPassword", "new-password");
        return request;
    }

    @Test void wrongOtpAttemptSurvivesHttpError() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        OtpCode otp = otps.save(OtpCode.builder().email(email)
                .purpose(OtpCode.Purpose.EMAIL_VERIFICATION).otpHash(hash("123456"))
                .expiresAt(LocalDateTime.now().plusMinutes(5)).build());
        var request = new VerifyOtpRequest();
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "purpose", "EMAIL_VERIFICATION");
        ReflectionTestUtils.setField(request, "otp", "000000");
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThrows(AppException.class, () -> auth.verifyOtp(request));
            assertEquals(attempt, otps.findById(otp.getId()).orElseThrow().getAttempts());
        }
        ReflectionTestUtils.setField(request, "otp", "123456");
        assertThrows(AppException.class, () -> auth.verifyOtp(request));
        assertEquals(5, otps.findById(otp.getId()).orElseThrow().getAttempts());
    }

    static String hash(String raw) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Configuration
    @Import(com.tranbac.chiptripbe.support.SecurityPersistenceConfig.class)
    static class Config {
        @Bean PasswordEncoder encoder() { return mock(PasswordEncoder.class); }
        @Bean AuthService authService(UserRepository users, RoleRepository roles, RefreshTokenRepository refresh,
                EmailVerificationTokenRepository email, PasswordResetTokenRepository reset, OtpCodeRepository otps,
                PlatformTransactionManager transactionManager, PasswordEncoder encoder) {
            return new AuthServiceImpl(users, roles, refresh, email, reset, otps, mock(JwtProvider.class),
                    encoder, mock(EmailService.class), new MailProperties(), mock(GoogleTokenVerifier.class), transactionManager);
        }
    }
}
