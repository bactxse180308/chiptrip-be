package com.tranbac.chiptripbe.module.place.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.place.dto.PlaceDto;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class PlaceQueryServiceImpl implements PlaceQueryService {

    private final PlaceCacheRepository placeCacheRepository;
    private final ObjectMapper objectMapper;

    @Override
    public PlaceDto getPlace(Long id) {
        PlaceCache place = placeCacheRepository.findById(id)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy địa điểm"));
        return toDto(place);
    }

    private PlaceDto toDto(PlaceCache p) {
        return PlaceDto.builder()
                .id(p.getId())
                .name(p.getName())
                .address(p.getAddress())
                .latitude(p.getLatitude())
                .longitude(p.getLongitude())
                .rating(p.getRating())
                .reviewCount(p.getReviewCount())
                .openState(p.getOpenState())
                .openingHours(parseOpeningHours(p.getOpeningHoursJson()))
                .photos(parsePhotos(p.getPhotosJson()))
                .reviews(parseReviews(p.getReviewsJson()))
                .phone(p.getPhone())
                .website(p.getWebsite())
                .bookingUrl(p.getBookingUrl())
                .pricePerNightVnd(p.getPricePerNightVnd())
                .build();
    }

    private List<PlaceDto.OpeningHour> parseOpeningHours(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<Map<String, String>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            return raw.stream()
                    .map(m -> PlaceDto.OpeningHour.builder()
                            .day(m.get("day"))
                            .hours(m.get("hours"))
                            .build())
                    .toList();
        } catch (Exception e) {
            return null;
        }
    }

    private List<PlaceDto.PlacePhoto> parsePhotos(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<Map<String, String>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            return raw.stream()
                    .map(m -> PlaceDto.PlacePhoto.builder()
                            .url(m.get("url"))
                            .thumbnail(m.getOrDefault("thumbnail", m.get("url")))
                            .build())
                    .toList();
        } catch (Exception e) {
            return null;
        }
    }

    private List<PlaceDto.PlaceReview> parseReviews(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            return raw.stream()
                    .map(m -> {
                        Object ratingVal = m.get("rating");
                        BigDecimal rating = null;
                        if (ratingVal instanceof Number) {
                            rating = BigDecimal.valueOf(((Number) ratingVal).doubleValue());
                        }
                        return PlaceDto.PlaceReview.builder()
                                .author((String) m.get("author"))
                                .avatar((String) m.get("avatar"))
                                .rating(rating)
                                .time((String) m.get("time"))
                                .text((String) m.get("text"))
                                .build();
                    })
                    .toList();
        } catch (Exception e) {
            return null;
        }
    }
}
