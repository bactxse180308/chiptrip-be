package com.tranbac.chiptripbe.common.util;

public final class PlaceQueryUtil {

    private PlaceQueryUtil() {}

    /**
     * Build search query gửi Goong/SerpApi.
     * KHÔNG nhét destination: searchQuery từ AI đã chứa tỉnh/thành (theo system prompt).
     * Chỉ đảm bảo có hậu tố ", Việt Nam".
     */
    public static String buildPlaceQuery(String placeName) {
        if (placeName == null || placeName.isBlank()) return null;
        String trimmed = placeName.trim();
        if (trimmed.toLowerCase().contains("việt nam")) return trimmed;
        return trimmed + ", Việt Nam";
    }
}
