package com.tranbac.chiptripbe.module.geocoding.service;

import com.tranbac.chiptripbe.module.geocoding.dto.GeocodingResult;

public interface GeocodingService {
    /**
     * Tìm kiếm tọa độ địa điểm qua text search.
     *
     * @param query       chuỗi tìm kiếm từ AI (vd "Bánh căn Nhà Chung Đà Lạt")
     * @param destination tên tỉnh/thành phố dùng làm context bổ sung nếu query chưa có
     * @return kết quả địa điểm đầu tiên tìm được, hoặc null nếu không tìm thấy / lỗi
     */
    GeocodingResult searchPlace(String query, String destination);
}