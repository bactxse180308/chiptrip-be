package com.tranbac.chiptripbe.common.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Map tên thành phố/tỉnh VN → mã sân bay IATA cho SerpApi Google Flights.
 * Khớp theo "normalized input chứa key" (vì destination có thể là "Đà Lạt, Lâm Đồng").
 * Key dài/đặc thù đặt trước để tránh khớp nhầm.
 */
public final class VietnamAirports {

    private VietnamAirports() {}

    // LinkedHashMap giữ thứ tự ưu tiên: cụm dài/đặc thù trước cụm ngắn.
    private static final Map<String, String> CITY_TO_IATA = new LinkedHashMap<>();
    static {
        CITY_TO_IATA.put("thanh pho ho chi minh", "SGN");
        CITY_TO_IATA.put("ho chi minh", "SGN");
        CITY_TO_IATA.put("sai gon", "SGN");
        CITY_TO_IATA.put("tphcm", "SGN");
        CITY_TO_IATA.put("ha noi", "HAN");
        CITY_TO_IATA.put("da nang", "DAD");
        CITY_TO_IATA.put("da lat", "DLI");
        CITY_TO_IATA.put("lam dong", "DLI");
        CITY_TO_IATA.put("nha trang", "CXR");
        CITY_TO_IATA.put("cam ranh", "CXR");
        CITY_TO_IATA.put("khanh hoa", "CXR");
        CITY_TO_IATA.put("phu quoc", "PQC");
        CITY_TO_IATA.put("kien giang", "PQC");
        CITY_TO_IATA.put("thua thien hue", "HUI");
        CITY_TO_IATA.put("hue", "HUI");
        CITY_TO_IATA.put("hai phong", "HPH");
        CITY_TO_IATA.put("quy nhon", "UIH");
        CITY_TO_IATA.put("binh dinh", "UIH");
        CITY_TO_IATA.put("buon ma thuot", "BMV");
        CITY_TO_IATA.put("dak lak", "BMV");
        CITY_TO_IATA.put("daklak", "BMV");
        CITY_TO_IATA.put("pleiku", "PXU");
        CITY_TO_IATA.put("gia lai", "PXU");
        CITY_TO_IATA.put("nghe an", "VII");
        CITY_TO_IATA.put("vinh", "VII");
        CITY_TO_IATA.put("thanh hoa", "THD");
        CITY_TO_IATA.put("dong hoi", "VDH");
        CITY_TO_IATA.put("quang binh", "VDH");
        CITY_TO_IATA.put("chu lai", "VCL");
        CITY_TO_IATA.put("quang nam", "VCL");
        CITY_TO_IATA.put("tam ky", "VCL");
        CITY_TO_IATA.put("tuy hoa", "TBB");
        CITY_TO_IATA.put("phu yen", "TBB");
        CITY_TO_IATA.put("con dao", "VCS");
        CITY_TO_IATA.put("ca mau", "CAH");
        CITY_TO_IATA.put("rach gia", "VKG");
        CITY_TO_IATA.put("dien bien", "DIN");
        CITY_TO_IATA.put("can tho", "VCA");
        CITY_TO_IATA.put("van don", "VDO");
        CITY_TO_IATA.put("quang ninh", "VDO");
        CITY_TO_IATA.put("ha long", "VDO");
    }

    /** Trả mã IATA nếu nhận diện được sân bay gần nhất cho tên thành phố/tỉnh. */
    public static Optional<String> resolve(String cityOrProvince) {
        if (cityOrProvince == null || cityOrProvince.isBlank()) return Optional.empty();
        String norm = normalize(cityOrProvince);
        for (Map.Entry<String, String> e : CITY_TO_IATA.entrySet()) {
            if (norm.contains(e.getKey())) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    private static String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[àáạảãâầấậẩẫăằắặẳẵ]", "a")
                .replaceAll("[èéẹẻẽêềếệểễ]", "e")
                .replaceAll("[ìíịỉĩ]", "i")
                .replaceAll("[òóọỏõôồốộổỗơờớợởỡ]", "o")
                .replaceAll("[ùúụủũưừứựửữ]", "u")
                .replaceAll("[ỳýỵỷỹ]", "y")
                .replaceAll("[đ]", "d")
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
