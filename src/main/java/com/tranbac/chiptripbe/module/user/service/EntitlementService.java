package com.tranbac.chiptripbe.module.user.service;

import com.tranbac.chiptripbe.module.user.dto.response.EntitlementsResponse;

public interface EntitlementService {

    /** Action LIVE chỉ dành cho Premium (đổi hoạt động). Normal → 403 PREMIUM_REQUIRED. */
    void requirePremium(Long userId);

    /** Snapshot quyền + giới hạn cho FE gate UI. */
    EntitlementsResponse getEntitlements(Long userId);
}
