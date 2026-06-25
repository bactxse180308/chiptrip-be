package com.tranbac.chiptripbe.module.user.service;

import com.tranbac.chiptripbe.module.user.enums.CreditSource;

public interface CreditService {

    /**
     * Tiêu 1 lượt generate. Gọi TRONG tx persist, SAU khi persist trip thành công.
     * Thứ tự: paid (1.00) trước, hết paid mới rơi xuống trial (1 lượt/ngày, không cộng dồn).
     *
     * @return PAID hoặc TRIAL.
     * @throws com.tranbac.chiptripbe.common.exception.AppException 402 DAILY_TRIAL_USED nếu hết sạch
     *         → rollback toàn bộ trip (user KHÔNG mất lượt).
     */
    CreditSource consumeForGenerate(Long userId);
}
