package com.tranbac.chiptripbe.module.user.service.impl;

import com.tranbac.chiptripbe.common.service.mail.EmailService;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.user.dto.request.GrantCreditsRequest;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.auth.repository.EmailVerificationTokenRepository;
import com.tranbac.chiptripbe.module.auth.repository.RefreshTokenRepository;
import com.tranbac.chiptripbe.module.payment.repository.PaymentOrderRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.user.dto.response.AdminUserDetailResponse;
import com.tranbac.chiptripbe.module.user.entity.Role;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.RoleRepository;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final long USER_ID = 10L;

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private TripRepository tripRepository;
    @Mock private AiUsageRepository aiUsageRepository;
    @Mock private PaymentOrderRepository paymentOrderRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @InjectMocks
    private UserServiceImpl service;

    @Test
    void adminGrantUsesAtomicUpdateAndReturnsFreshFractionalBalance() {
        User fresh = user();
        fresh.setAiCredits(1);
        fresh.setAiCreditUnits(175);
        when(userRepository.addCredits(USER_ID, 1)).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(fresh));
        var result = service.adminGrantCredits(USER_ID, grant(1));
        assertEquals(175, result.getAiCreditUnits());
        assertEquals(new BigDecimal("1.75"), result.getAiCreditBalance());
        var order = inOrder(userRepository);
        order.verify(userRepository).addCredits(USER_ID, 1);
        order.verify(userRepository).findById(USER_ID);
        verify(userRepository, never()).save(any());
    }

    @Test
    void adminGrantRejectsInvalidAmountBeforeDatabaseAccess() {
        for (Integer amount : new Integer[]{null, -1, 0, Integer.MAX_VALUE}) {
            assertThrows(AppException.class, () -> service.adminGrantCredits(USER_ID, grant(amount)));
        }
        verifyNoInteractions(userRepository);
    }

    @Test
    void adminGrantCannotSilentlySucceedOnOverflowOrMissingUser() {
        when(userRepository.existsById(USER_ID)).thenReturn(true, false);
        assertThrows(AppException.class, () -> service.adminGrantCredits(USER_ID, grant(1)));
        assertThrows(AppException.class, () -> service.adminGrantCredits(USER_ID, grant(1)));
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findById(any());
    }

    private GrantCreditsRequest grant(Integer amount) {
        var request = new GrantCreditsRequest();
        ReflectionTestUtils.setField(request, "amount", amount);
        return request;
    }

    @Test
    void getAdminUserDetail_readsAiAggregateRowWithoutClassCastException() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(tripRepository.findByUserId(eq(USER_ID), any(Pageable.class))).thenReturn(Page.empty());
        when(aiUsageRepository.aggregateByUserId(USER_ID))
                .thenReturn(List.<Object[]>of(new Object[]{new Object[]{2L, 300L, 700L, new BigDecimal("0.0123")}}));
        when(refreshTokenRepository.countByUserIdAndRevokedFalse(USER_ID)).thenReturn(1L);
        when(paymentOrderRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());

        AdminUserDetailResponse response = assertDoesNotThrow(() -> service.getAdminUserDetail(USER_ID));

        assertEquals(2L, response.getAiUsage().getTotalCount());
        assertEquals(300L, response.getAiUsage().getTotalTokensIn());
        assertEquals(700L, response.getAiUsage().getTotalTokensOut());
        assertEquals(new BigDecimal("0.0123"), response.getAiUsage().getTotalCostUsd());
    }

    private User user() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 16, 9, 0);
        User user = User.builder()
                .email("user@example.com")
                .fullName("Test User")
                .aiCredits(5)
                .isActive(true)
                .emailVerified(true)
                .role(Role.builder().name("ROLE_USER").build())
                .build();
        user.setId(USER_ID);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }
}
