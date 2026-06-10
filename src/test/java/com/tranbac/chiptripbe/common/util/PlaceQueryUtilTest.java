package com.tranbac.chiptripbe.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verify {@link PlaceQueryUtil#buildPlaceQuery(String)} fix bug "gộp/lặp tên thành phố":
 * - Không append destination khác vào query (bug TRANSPORT xuất phát).
 * - Không lặp tỉnh nếu searchQuery đã chứa tỉnh.
 * - Không double-append "Việt Nam".
 */
class PlaceQueryUtilTest {

    @Test
    void buildPlaceQuery_transportFromDifferentCity_doesNotMergeTwoCities() {
        // Bug cũ: "Sân bay Nội Bài Hà Nội" + destination "Đà Lạt" → "Sân bay Nội Bài Hà Nội Đà Lạt Việt Nam"
        // Sau fix: chỉ append ", Việt Nam"
        String result = PlaceQueryUtil.buildPlaceQuery("Sân bay Nội Bài Hà Nội");
        assertEquals("Sân bay Nội Bài Hà Nội, Việt Nam", result);
    }

    @Test
    void buildPlaceQuery_searchQueryAlreadyContainsCity_doesNotDuplicateCity() {
        // Bug cũ: "Hồ Tuyền Lâm Đà Lạt" + destination "Đà Lạt" → "Hồ Tuyền Lâm Đà Lạt Đà Lạt Việt Nam"
        // Sau fix: chỉ thêm ", Việt Nam"
        String result = PlaceQueryUtil.buildPlaceQuery("Hồ Tuyền Lâm Đà Lạt");
        assertEquals("Hồ Tuyền Lâm Đà Lạt, Việt Nam", result);
    }

    @Test
    void buildPlaceQuery_alreadyContainsVietNam_returnsAsIs() {
        String result = PlaceQueryUtil.buildPlaceQuery("Quán ăn Hà Nội Việt Nam");
        assertEquals("Quán ăn Hà Nội Việt Nam", result);
    }

    @Test
    void buildPlaceQuery_nullOrBlank_returnsNull() {
        assertNull(PlaceQueryUtil.buildPlaceQuery(null));
        assertNull(PlaceQueryUtil.buildPlaceQuery(""));
        assertNull(PlaceQueryUtil.buildPlaceQuery("   "));
    }
}
