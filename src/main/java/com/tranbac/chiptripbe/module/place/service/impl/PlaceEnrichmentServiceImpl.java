package com.tranbac.chiptripbe.module.place.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.module.geocoding.client.GoongClient;
import com.tranbac.chiptripbe.module.geocoding.client.SerpApiClient;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceCacheService;
import com.tranbac.chiptripbe.module.place.service.PlaceEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
class PlaceEnrichmentServiceImpl implements PlaceEnrichmentService {

    private final PlaceCacheService placeCacheService;
    private final PlaceCacheRepository placeCacheRepository;
    private final GoongClient goongClient;
    private final SerpApiClient serpApiClient;
    private final ObjectMapper objectMapper;

    private String cleanPlaceName(String name) {
        if (name == null) return "";
        String regex = "(?i)^(tham quan|check[- ]in|checkin|an trua|an toi|an sang|an|uong cafe|uong|binh minh tai|san may|di|tha den hoa dang|mua sam tai|mua sam|vieng|trai nghiem|ngam hoang hon|trekking|loi bo|dao bo|thuong thuc|check in|uong cà phe|uong ca phe|an trưa|an tối|an sáng|ăn trưa|ăn tối|ăn sáng|ăn|uống cafe|uống cà phê|uống ca phe|uống|bình minh tại|săn mây|đi|thả đèn hoa đăng|mua sắm tại|mua sắm|viếng|trải nghiệm|ngắm hoàng hôn|thưởng thức)\\s+";
        String cleaned = name.replaceAll(regex, "").trim();
        if (cleaned.length() > 0) {
            cleaned = Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
        }
        return cleaned;
    }

    /**
     * NOT_SUPPORTED: không mở tx bao quanh Goong/SerpApi HTTP calls.
     * Việc save PlaceCache đã mở tx riêng qua PlaceCacheService.save() (REQUIRES_NEW).
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<PlaceCache> resolvePlace(String placeName, String destination) {
        if (placeName == null || placeName.isBlank()) return Optional.empty();

        String cleanedName = cleanPlaceName(placeName);
        String normalized = normalize(cleanedName);
        String normalizedDestination = normalize(destination);

        // 1. Cache theo cặp (normalizedName, normalizedDestination)
        Optional<PlaceCache> fresh = placeCacheService.findFreshCache(normalized, normalizedDestination);
        if (fresh.isPresent()) {
            log.debug("Place cache hit: name='{}', dest='{}'", normalized, normalizedDestination);
            return fresh;
        }

        // 2. Goong geocode — bắt buộc để có lat/lng
        String geocodeQuery = buildGeocodeQuery(cleanedName, destination);
        Optional<GoongClient.GeocodeResult> geoOpt = goongClient.forwardGeocode(geocodeQuery);
        if (geoOpt.isEmpty()) {
            log.debug("Goong geocode: không tìm thấy '{}'", geocodeQuery);
            return Optional.empty();
        }

        GoongClient.GeocodeResult geo = geoOpt.get();

        // 2b. Verify Goong result match destination — tránh lưu lat/lng tỉnh khác
        if (!addressMatchesDestination(geo.formattedAddress(), normalizedDestination)) {
            log.warn("Goong mismatch: query='{}', address='{}', expectedDestination='{}'",
                    geocodeQuery, geo.formattedAddress(), destination);
            return Optional.empty();
        }

        // 3. Dedup theo goongPlaceId (an toàn với duplicate trong DB)
        if (geo.placeId() != null && !geo.placeId().isBlank()) {
            Optional<PlaceCache> byPlaceId = placeCacheRepository.findBestByGoongPlaceId(geo.placeId());
            if (byPlaceId.isPresent() && byPlaceId.get().isSerpEnriched()) {
                log.debug("Place cache dedup by goongPlaceId='{}', reuse id={}", geo.placeId(), byPlaceId.get().getId());
                return byPlaceId;
            }
        }

        // 4. Lấy row cũ để update thay vì insert mới
        Optional<PlaceCache> existing = (normalizedDestination == null || normalizedDestination.isBlank())
                ? placeCacheRepository.findFirstByNormalizedNameAndNormalizedDestinationIsNull(normalized)
                : placeCacheRepository.findFirstByNormalizedNameAndNormalizedDestination(normalized, normalizedDestination);
        if (existing.isEmpty() && geo.placeId() != null && !geo.placeId().isBlank()) {
            existing = placeCacheRepository.findBestByGoongPlaceId(geo.placeId());
        }

        PlaceCache toSave;
        if (existing.isPresent()) {
            toSave = existing.get();
        } else {
            toSave = new PlaceCache();
            toSave.setNormalizedName(normalized);
            toSave.setNormalizedDestination(normalizedDestination);
            toSave.setName(cleanedName);
        }

        // Đảm bảo các field key luôn có
        toSave.setNormalizedName(normalized);
        toSave.setNormalizedDestination(normalizedDestination);
        toSave.setLatitude(geo.lat());
        toSave.setLongitude(geo.lng());
        toSave.setGoongPlaceId(geo.placeId());
        toSave.setAddress(geo.formattedAddress());
        toSave.setProvinceName(geo.provinceName());
        toSave.setCommuneName(geo.communeName());
        toSave.setLastSyncedAt(LocalDateTime.now());

        // 5. SerpApi enrich (best-effort: nếu fail vẫn lưu data Goong)
        SerpEnrichOutcome outcome = enrichWithSerpApi(toSave, cleanedName, destination, normalizedDestination, geo, placeName);
        toSave.setSerpEnriched(outcome.enriched());
        toSave.setSerpSyncedAt(outcome.syncedAt());
        try {
            PlaceCache saved = placeCacheService.save(toSave);
            log.debug("Saved place cache id={} normalized='{}' dest='{}' serpEnriched={}",
                    saved.getId(), normalized, normalizedDestination, outcome.enriched());
            return Optional.of(saved);
        } catch (DataIntegrityViolationException ex) {
            String placeId = toSave.getGoongPlaceId();
            log.warn("Duplicate goong_place_id='{}' on insert (concurrent writer); refetching best existing row", placeId);
            return placeCacheRepository.findBestByGoongPlaceId(placeId);
        }
    }

    /**
     * Enrich hotel-specific data (booking link + price/night) khi activity là ACCOMMODATION.
     * Best-effort: fail-soft (SerpApi Hotels không hoạt động → place_cache giữ nguyên).
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void enrichAccommodation(PlaceCache cache, LocalDate checkIn, LocalDate checkOut, Integer adults) {
        if (cache == null) return;
        try {
            Optional<SerpApiClient.HotelData> hotelOpt = serpApiClient.searchHotel(cache.getName(), checkIn, checkOut, adults);
            if (hotelOpt.isEmpty()) return;
            SerpApiClient.HotelData h = hotelOpt.get();

            boolean changed = false;
            if (h.bookingLink() != null && !h.bookingLink().isBlank()
                    && !h.bookingLink().equals(cache.getBookingUrl())) {
                cache.setBookingUrl(h.bookingLink());
                changed = true;
            }
            if (h.pricePerNightVnd() != null && !h.pricePerNightVnd().equals(cache.getPricePerNightVnd())) {
                cache.setPricePerNightVnd(h.pricePerNightVnd());
                changed = true;
            }
            if (h.rating() != null && !h.rating().equals(cache.getRating())) {
                cache.setRating(h.rating());
                changed = true;
            }
            
            if (h.propertyToken() != null && !h.propertyToken().isBlank()) {
                List<Map<String, String>> photos = serpApiClient.fetchHotelPhotos(h.propertyToken());
                if (photos != null && !photos.isEmpty()) {
                    try {
                        cache.setPhotosJson(objectMapper.writeValueAsString(photos));
                        changed = true;
                    } catch (Exception ex) {
                        log.warn("Failed to serialize hotel photos: {}", ex.getMessage());
                    }
                }

                List<Map<String, Object>> reviews = serpApiClient.fetchHotelReviews(h.propertyToken());
                if (reviews != null && !reviews.isEmpty()) {
                    try {
                        cache.setReviewsJson(objectMapper.writeValueAsString(reviews));
                        changed = true;
                    } catch (Exception ex) {
                        log.warn("Failed to serialize hotel reviews: {}", ex.getMessage());
                    }
                }
            }

            if (changed) {
                placeCacheService.save(cache);
                log.info("Hotel enrich saved: cache id={} name='{}' price={} VNĐ booking={}",
                        cache.getId(), cache.getName(), cache.getPricePerNightVnd(), cache.getBookingUrl());
            }
        } catch (Exception e) {
            log.warn("Hotel enrich failed for cache id={} name='{}': {}",
                    cache.getId(), cache.getName(), e.getMessage());
        }
    }

    /** Kết quả của 1 lần attempt SerpApi. */
    private record SerpEnrichOutcome(boolean enriched, LocalDateTime syncedAt) {}

    /**
     * Gọi SerpApi, chọn candidate khớp nhất, set field enrich lên entity.
     * Tiêu chí "enriched" mới: hasBasic && hasPhotos && (hasOpeningHours || hasReviews).
     */
    private SerpEnrichOutcome enrichWithSerpApi(PlaceCache entity,
                                                String cleanedName,
                                                String destination,
                                                String normalizedDestination,
                                                GoongClient.GeocodeResult geo,
                                                String originalName) {
        try {
            String serpQuery = cleanedName + (destination != null ? " " + destination : "") + " Việt Nam";
            List<SerpApiClient.PlaceData> candidates = serpApiClient.searchPlaceCandidates(serpQuery);
            if (candidates.isEmpty()) {
                log.debug("SerpApi không trả về candidate cho '{}', áp dụng backoff", serpQuery);
                return new SerpEnrichOutcome(false, LocalDateTime.now());
            }

            SerpApiClient.PlaceData s = pickBestCandidate(candidates, cleanedName, normalizedDestination, geo);
            if (s == null) {
                log.warn("SerpApi: không có candidate khớp destination='{}' cho query='{}' ({} candidates) — bỏ qua enrichment",
                        destination, serpQuery, candidates.size());
                return new SerpEnrichOutcome(false, LocalDateTime.now());
            }

            entity.setSerpDataId(s.dataId());
            entity.setSerpPlaceId(s.placeId());
            entity.setSerpTitle(s.title());
            entity.setPlaceType(s.type());
            entity.setRating(s.rating());
            entity.setReviewCount(s.reviewCount());
            entity.setOpeningHoursJson(s.operatingHoursJson());
            entity.setOpenState(resolveOpenState(s));
            // hoursText: prefer s.hours() (chuỗi ngắn), fallback raw openState text (truncate tại 255)
            String hoursText = s.hours();
            if (hoursText == null && s.openState() != null && !s.openState().isBlank()) {
                String raw = s.openState();
                hoursText = raw.length() > 255 ? raw.substring(0, 255) : raw;
            }
            entity.setHoursText(hoursText);
            entity.setPhone(s.phone());
            entity.setWebsite(s.website());

            String photosId = s.dataId() != null ? s.dataId() : s.placeId();
            List<Map<String, String>> photos = List.of();
            if (photosId != null && !photosId.isBlank()) {
                photos = safeFetchPhotos(photosId);
            }

            boolean photosWritten = false;
            if (photos != null && !photos.isEmpty()) {
                try {
                    entity.setPhotosJson(objectMapper.writeValueAsString(photos));
                    photosWritten = true;
                } catch (Exception ex) {
                    log.warn("Failed to serialize photos list to JSON: {}", ex.getMessage());
                }
            }
            if (!photosWritten && s.thumbnailUrl() != null) {
                String fallback = buildPhotosJson(s.thumbnailUrl());
                if (fallback != null) {
                    entity.setPhotosJson(fallback);
                }
            }

            String reviewsId = s.dataId() != null ? s.dataId() : s.placeId();
            List<Map<String, Object>> reviews = List.of();
            if (reviewsId != null && !reviewsId.isBlank()) {
                reviews = safeFetchReviews(reviewsId);
            }
            if (reviews != null && !reviews.isEmpty()) {
                try {
                    entity.setReviewsJson(objectMapper.writeValueAsString(reviews));
                } catch (Exception ex) {
                    log.warn("Failed to serialize reviews list to JSON: {}", ex.getMessage());
                }
            }

            boolean hasBasic = s.rating() != null || s.reviewCount() != null
                    || s.phone() != null || s.website() != null;
            boolean hasPhotos = (photos != null && !photos.isEmpty()) || s.thumbnailUrl() != null;
            boolean hasOpeningHours = s.operatingHoursJson() != null
                    || s.hours() != null || s.openState() != null;
            boolean hasReviews = reviews != null && !reviews.isEmpty();
            boolean enriched = hasBasic && hasPhotos && (hasOpeningHours || hasReviews);

            log.info("SerpApi enrich: name='{}', title='{}', rating={}, reviewCount={}, dataId={}, placeId={}, "
                            + "photosCount={}, reviewsCount={}, hasBasic={}, hasPhotos={}, hasOpeningHours={}, hasReviews={}, enriched={}",
                    cleanedName, s.title(), s.rating(), s.reviewCount(), s.dataId(), s.placeId(),
                    photos != null ? photos.size() : 0,
                    reviews != null ? reviews.size() : 0,
                    hasBasic, hasPhotos, hasOpeningHours, hasReviews, enriched);

            return new SerpEnrichOutcome(enriched, LocalDateTime.now());

        } catch (Exception e) {
            log.warn("SerpApi enrich failed for '{}', dùng Goong-only data: {}", originalName, e.getMessage());
            return new SerpEnrichOutcome(false, null);
        }
    }

    /**
     * Ưu tiên candidate có address chứa destination; tie-break theo title gần cleanedName
     * và (nếu có gps_coordinates) khoảng cách tới điểm Goong trả về.
     * Không fallback mù quáng candidate đầu tiên — thà bỏ enrichment còn hơn ghép sai.
     */
    private SerpApiClient.PlaceData pickBestCandidate(List<SerpApiClient.PlaceData> candidates,
                                                      String cleanedName,
                                                      String normalizedDestination,
                                                      GoongClient.GeocodeResult geo) {
        if (candidates.isEmpty()) return null;

        String normalizedClean = normalize(cleanedName);

        SerpApiClient.PlaceData best = null;
        double bestScore = -1;
        boolean bestAddressMatches = false;

        for (SerpApiClient.PlaceData c : candidates) {
            boolean addressMatches = addressMatchesDestination(c.address(), normalizedDestination);
            double titleScore = titleSimilarity(normalize(c.title()), normalizedClean);
            double distanceScore = distanceScore(c, geo);

            // Score: ưu tiên address match nhiều hơn title/distance
            double score = (addressMatches ? 100.0 : 0.0) + titleScore * 10.0 + distanceScore;

            if (score > bestScore) {
                bestScore = score;
                best = c;
                bestAddressMatches = addressMatches;
            }
        }

        // Bắt buộc address phải khớp destination, nếu destination biết được
        if (normalizedDestination != null && !normalizedDestination.isBlank() && !bestAddressMatches) {
            return null;
        }
        return best;
    }

    private double titleSimilarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return 0.0;
        if (a.equals(b)) return 1.0;
        if (a.contains(b) || b.contains(a)) return 0.8;

        String[] tokensA = a.split("\\s+");
        String[] tokensB = b.split("\\s+");
        int matches = 0;
        for (String ta : tokensA) {
            for (String tb : tokensB) {
                if (ta.equals(tb) && ta.length() > 2) {
                    matches++;
                    break;
                }
            }
        }
        int maxTokens = Math.max(tokensA.length, tokensB.length);
        return maxTokens == 0 ? 0.0 : (double) matches / maxTokens;
    }

    /**
     * Score 0..1 dựa trên khoảng cách candidate đến Goong result.
     * Nếu candidate không có gps → 0. Cùng vị trí → 1; >50km → 0.
     */
    private double distanceScore(SerpApiClient.PlaceData c, GoongClient.GeocodeResult geo) {
        if (c.latitude() == null || c.longitude() == null
                || geo == null || geo.lat() == null || geo.lng() == null) {
            return 0.0;
        }
        double km = haversineKm(c.latitude(), c.longitude(), geo.lat(), geo.lng());
        if (km >= 50.0) return 0.0;
        return 1.0 - (km / 50.0);
    }

    private double haversineKm(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1.doubleValue())) * Math.cos(Math.toRadians(lat2.doubleValue()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }

    /**
     * True nếu formattedAddress chứa destination sau normalize.
     * Trả true khi destination null/blank để không over-reject ở trường hợp không có context.
     */
    private boolean addressMatchesDestination(String address, String normalizedDestination) {
        if (normalizedDestination == null || normalizedDestination.isBlank()) return true;
        if (address == null || address.isBlank()) return false;
        String normalizedAddress = normalize(address);
        return normalizedAddress.contains(normalizedDestination);
    }

    private String resolveOpenState(SerpApiClient.PlaceData s) {
        if (s.openState() != null && !s.openState().isBlank()) {
            String lower = s.openState().toLowerCase();
            if (lower.contains("đang mở") || lower.contains("open")) return "OPEN";
            if (lower.contains("đã đóng") || lower.contains("closed")) return "CLOSED";
            // text dài/không rõ → trả null; raw text sẽ vào hoursText
        }
        if (s.openNow() != null) return s.openNow() ? "OPEN" : "CLOSED";
        return null;
    }

    private List<Map<String, String>> safeFetchPhotos(String dataId) {
        try {
            return serpApiClient.fetchPhotos(dataId);
        } catch (Exception ex) {
            log.warn("SerpApi fetchPhotos failed for dataId='{}': {}", dataId, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> safeFetchReviews(String dataId) {
        try {
            return serpApiClient.fetchReviews(dataId);
        } catch (Exception ex) {
            log.warn("SerpApi fetchReviews failed for dataId='{}': {}", dataId, ex.getMessage());
            return List.of();
        }
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    /**
     * Chuẩn hóa tên: bỏ dấu tiếng Việt, lowercase, trim khoảng trắng.
     * Dùng để tra cứu và tránh duplicate cache.
     */
    private String normalize(String name) {
        if (name == null) return null;
        return name.toLowerCase()
                .replaceAll("[àáạảãâầấậẩẫăằắặẳẵ]", "a")
                .replaceAll("[èéẹẻẽêềếệểễ]", "e")
                .replaceAll("[ìíịỉĩ]", "i")
                .replaceAll("[òóọỏõôồốộổỗơờớợởỡ]", "o")
                .replaceAll("[ùúụủũưừứựửữ]", "u")
                .replaceAll("[ỳýỵỷỹ]", "y")
                .replaceAll("[đ]", "d")
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String buildGeocodeQuery(String placeName, String destination) {
        if (destination == null || destination.isBlank()) return placeName + ", Việt Nam";
        if (placeName.toLowerCase().contains(destination.toLowerCase())) return placeName + ", Việt Nam";
        return placeName + ", " + destination + ", Việt Nam";
    }

    private String buildPhotosJson(String thumbnailUrl) {
        try {
            return objectMapper.writeValueAsString(List.of(Map.of("url", thumbnailUrl, "thumbnail", thumbnailUrl)));
        } catch (Exception e) {
            return null;
        }
    }
}
