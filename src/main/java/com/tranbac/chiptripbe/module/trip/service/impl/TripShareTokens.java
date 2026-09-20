package com.tranbac.chiptripbe.module.trip.service.impl;

import java.util.UUID;

/** v1 tokens may have been exposed in public responses; do not accept them after deployment. */
final class TripShareTokens {
    private TripShareTokens() { }
    static String create() { return "v2_" + UUID.randomUUID().toString().replace("-", ""); }
    static boolean isCurrent(String token) { return token != null && token.matches("v2_[0-9a-f]{32}"); }
}
