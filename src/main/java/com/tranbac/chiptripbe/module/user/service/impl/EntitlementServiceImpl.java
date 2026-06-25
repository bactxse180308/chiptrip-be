package com.tranbac.chiptripbe.module.user.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.user.dto.response.EntitlementsResponse;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import com.tranbac.chiptripbe.module.user.service.EntitlementPolicy;
import com.tranbac.chiptripbe.module.user.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class EntitlementServiceImpl implements EntitlementService {

    private final UserRepository userRepository;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Override
    public void requirePremium(Long userId) {
        Integer units = userRepository.findAiCreditUnitsById(userId);
        if (units == null || units <= 0) {
            throw AppException.premiumRequired();   // 403 PREMIUM_REQUIRED
        }
    }

    @Override
    public EntitlementsResponse getEntitlements(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));

        boolean premium = user.isPremium();
        return EntitlementsResponse.builder()
                .accountType(premium ? "PREMIUM" : "NORMAL")
                .isPremium(premium)
                .trialCreditBalance(effectiveTrial(user))
                .paidCreditBalance(user.aiCreditBalance())
                .limits(EntitlementsResponse.Limits.builder()
                        .maxTripDays(EntitlementPolicy.maxTripDays(premium))
                        .maxStyles(EntitlementPolicy.maxStyles(premium))
                        .canExportPdf(premium)
                        .canRegenerate(premium)
                        .build())
                .build();
    }

    /**
     * Trial hiệu lực để hiển thị (read-only, KHÔNG ghi DB): mọi user được 1 lượt/ngày.
     * Nếu trial cấp hôm nay → đọc balance thật; nếu khác ngày/null → coi như còn 1 (sẽ được
     * grantDailyTrialIfNeeded cấp khi generate). Reset theo giờ VN, không theo UTC.
     */
    private int effectiveTrial(User user) {
        LocalDate today = LocalDate.now(VN);
        return today.equals(user.getTrialCreditDate()) ? user.getTrialCreditBalance() : 1;
    }
}
