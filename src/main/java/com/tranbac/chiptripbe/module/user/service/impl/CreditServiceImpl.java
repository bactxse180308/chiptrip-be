package com.tranbac.chiptripbe.module.user.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.user.enums.CreditSource;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import com.tranbac.chiptripbe.module.user.service.CreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
class CreditServiceImpl implements CreditService {

    private final UserRepository userRepository;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    /** 1.00 credit = 100 units. Paid balance lưu ở User.aiCreditUnits (nguồn chân lý duy nhất). */
    private static final int GENERATE_UNITS = 100;

    @Override
    public CreditSource consumeForGenerate(Long userId) {
        LocalDate today = LocalDate.now(VN);
        userRepository.grantDailyTrialIfNeeded(userId, today);   // SET=1 idempotent

        // 1) paid trước (atomic UPDATE … WHERE units >= 100)
        if (userRepository.deductCreditUnitsIfAvailable(userId, GENERATE_UNITS) > 0) {
            return CreditSource.PAID;
        }
        // 2) paid=0 → trial (query tự chặn nếu paid>0). KHÔNG dùng if(paid>0)... else — tránh
        //    race giữa 2 request báo lỗi oan; phải fall-through: thử paid, fail thì thử trial.
        if (userRepository.deductTrialCredit(userId) > 0) {
            return CreditSource.TRIAL;
        }
        // 3) hết sạch
        throw AppException.dailyTrialUsed();   // 402 DAILY_TRIAL_USED → rollback cả trip
    }
}
