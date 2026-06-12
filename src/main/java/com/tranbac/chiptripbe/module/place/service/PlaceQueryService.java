package com.tranbac.chiptripbe.module.place.service;

import com.tranbac.chiptripbe.module.place.dto.PlaceDto;

public interface PlaceQueryService {

    /** Chi tiết địa điểm đã enrich (rating, ảnh, giờ mở cửa, reviews) theo PlaceCache id. */
    PlaceDto getPlace(Long id);
}
