package com.tranbac.chiptripbe.module.user.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression: user mới (như luồng register/Google) phải là Normal, KHÔNG phải Premium oan. */
class UserTest {

    @Test
    void grantPreservesFractionalUnitsEvenWhenLegacyWholeBalanceIsStale() {
        User user = User.builder().aiCredits(9).aiCreditUnits(75).build();
        user.addWholeAiCredits(1);
        assertEquals(175, user.effectiveAiCreditUnits());
        assertEquals(1, user.getAiCredits());
    }

    @Test
    void newUser_defaultsToNormal_noPaidCredits() {
        // Giống AuthServiceImpl.register / google login: builder không set credits.
        User user = User.builder().email("new@e.com").fullName("New").build();

        assertEquals(0, user.effectiveAiCreditUnits(), "paid units mặc định phải = 0");
        assertFalse(user.isPremium(), "user mới KHÔNG được là Premium (bug 3 credit cũ)");
    }

    @Test
    void userWithPaidUnits_isPremium() {
        User user = User.builder().email("p@e.com").fullName("P").aiCredits(0).aiCreditUnits(100).build();
        assertTrue(user.isPremium());
    }
}
