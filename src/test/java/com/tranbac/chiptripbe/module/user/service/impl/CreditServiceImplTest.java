package com.tranbac.chiptripbe.module.user.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.user.enums.CreditSource;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CREDIT_PREMIUM_SPEC.md Mục 5.9:
 * (a) paid>0 → trừ paid; (b) paid=0 & trial=1 → trừ trial; (c) hết sạch → DAILY_TRIAL_USED;
 * (d) race: thua paid (deduct paid trả 0) → fall-through xuống trial, không lỗi oan.
 */
@ExtendWith(MockitoExtension.class)
class CreditServiceImplTest {

    private static final Long USER_ID = 5L;

    @Mock private UserRepository userRepository;

    @InjectMocks private CreditServiceImpl creditService;

    @Test
    void consumeForGenerate_paidAvailable_deductsPaid() {
        when(userRepository.deductCreditUnitsIfAvailable(USER_ID, 100)).thenReturn(1);

        CreditSource src = creditService.consumeForGenerate(USER_ID);

        assertEquals(CreditSource.PAID, src);
        verify(userRepository).grantDailyTrialIfNeeded(eq(USER_ID), any(LocalDate.class));
        verify(userRepository, never()).deductTrialCredit(USER_ID);
    }

    @Test
    void consumeForGenerate_paidZeroTrialOne_deductsTrial() {
        when(userRepository.deductCreditUnitsIfAvailable(USER_ID, 100)).thenReturn(0);
        when(userRepository.deductTrialCredit(USER_ID)).thenReturn(1);

        CreditSource src = creditService.consumeForGenerate(USER_ID);

        assertEquals(CreditSource.TRIAL, src);
    }

    @Test
    void consumeForGenerate_allExhausted_throwsDailyTrialUsed() {
        when(userRepository.deductCreditUnitsIfAvailable(USER_ID, 100)).thenReturn(0);
        when(userRepository.deductTrialCredit(USER_ID)).thenReturn(0);

        AppException ex = assertThrows(AppException.class, () -> creditService.consumeForGenerate(USER_ID));

        assertEquals("DAILY_TRIAL_USED", ex.getCode());
    }

    @Test
    void consumeForGenerate_lostPaidRace_fallsToTrial() {
        // 2 luồng cùng paid=1: luồng này thua (deduct paid trả 0) → rơi xuống trial, không lỗi oan
        when(userRepository.deductCreditUnitsIfAvailable(USER_ID, 100)).thenReturn(0);
        when(userRepository.deductTrialCredit(USER_ID)).thenReturn(1);

        assertEquals(CreditSource.TRIAL, creditService.consumeForGenerate(USER_ID));
    }
}
