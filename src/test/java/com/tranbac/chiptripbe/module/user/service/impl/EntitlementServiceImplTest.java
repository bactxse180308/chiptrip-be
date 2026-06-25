package com.tranbac.chiptripbe.module.user.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.user.dto.response.EntitlementsResponse;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** CREDIT_PREMIUM_SPEC.md Mục 5.4 — entitlements + requirePremium. */
@ExtendWith(MockitoExtension.class)
class EntitlementServiceImplTest {

    private static final Long USER_ID = 1L;

    @Mock private UserRepository userRepository;

    @InjectMocks private EntitlementServiceImpl service;

    @Test
    void requirePremium_paidZero_throwsPremiumRequired() {
        when(userRepository.findAiCreditUnitsById(USER_ID)).thenReturn(0);

        AppException ex = assertThrows(AppException.class, () -> service.requirePremium(USER_ID));

        assertEquals("PREMIUM_REQUIRED", ex.getCode());
    }

    @Test
    void requirePremium_paidPositive_doesNotThrow() {
        when(userRepository.findAiCreditUnitsById(USER_ID)).thenReturn(100);

        service.requirePremium(USER_ID);   // không ném
    }

    @Test
    void getEntitlements_normalUser_returnsNormalLimits() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                User.builder().email("n@e.com").fullName("N").aiCredits(0).aiCreditUnits(0).build()));

        EntitlementsResponse res = service.getEntitlements(USER_ID);

        assertEquals("NORMAL", res.getAccountType());
        assertFalse(res.isPremium());
        assertEquals(3, res.getLimits().getMaxTripDays());
        assertEquals(2, res.getLimits().getMaxStyles());
        assertFalse(res.getLimits().isCanExportPdf());
        assertFalse(res.getLimits().isCanRegenerate());
        // chưa cấp trial hôm nay → hiển thị còn 1 lượt
        assertEquals(1, res.getTrialCreditBalance());
    }

    @Test
    void getEntitlements_premiumUser_returnsPremiumLimits() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                User.builder().email("p@e.com").fullName("P").aiCredits(0).aiCreditUnits(300).build()));

        EntitlementsResponse res = service.getEntitlements(USER_ID);

        assertEquals("PREMIUM", res.getAccountType());
        assertTrue(res.isPremium());
        assertEquals(10, res.getLimits().getMaxTripDays());
        assertEquals(Integer.MAX_VALUE, res.getLimits().getMaxStyles());
        assertTrue(res.getLimits().isCanExportPdf());
        assertTrue(res.getLimits().isCanRegenerate());
    }
}
