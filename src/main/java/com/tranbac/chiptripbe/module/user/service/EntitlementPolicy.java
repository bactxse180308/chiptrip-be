package com.tranbac.chiptripbe.module.user.service;

/**
 * Hằng số giới hạn theo tier — 1 NƠI duy nhất, BE check & /entitlements response cùng đọc
 * để không lệch giữa lúc validate và lúc trả entitlements (CREDIT_PREMIUM_SPEC.md Mục 3, 5.4).
 */
public final class EntitlementPolicy {

    public static final int NORMAL_MAX_TRIP_DAYS = 3;
    public static final int PREMIUM_MAX_TRIP_DAYS = 10;

    public static final int NORMAL_MAX_STYLES = 5;
    /** Premium: không giới hạn styles — FE coi MAX_VALUE là "không giới hạn". */
    public static final int PREMIUM_MAX_STYLES = Integer.MAX_VALUE;

    private EntitlementPolicy() {}

    public static int maxTripDays(boolean premium) {
        return premium ? PREMIUM_MAX_TRIP_DAYS : NORMAL_MAX_TRIP_DAYS;
    }

    public static int maxStyles(boolean premium) {
        return premium ? PREMIUM_MAX_STYLES : NORMAL_MAX_STYLES;
    }
}
