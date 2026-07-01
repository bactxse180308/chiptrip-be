package com.tranbac.chiptripbe.module.place.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.util.PlaceQueryUtil;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
class PlaceEnrichmentServiceImpl implements PlaceEnrichmentService {

    /** Bán kính chấp nhận quanh anchor (destination/departure) khi địa chỉ không chứa destination. */
    private static final double ANCHOR_RADIUS_KM = 60.0;

    private static final int ANCHOR_CACHE_MAX = 1_000;

    private final PlaceCacheService placeCacheService;
    private final PlaceCacheRepository placeCacheRepository;
    private final GoongClient goongClient;
    private final SerpApiClient serpApiClient;
    private final ObjectMapper objectMapper;

    /** Cache anchor theo normalize(tên) — destination lặp lại nhiều giữa các trip. Chỉ cache hit. */
    private final ConcurrentHashMap<String, GeoAnchor> anchorCache = new ConcurrentHashMap<>();

    private String cleanPlaceName(String name) {
        if (name == null) return "";
        // Chỉ giữ pattern đa âm tiết hoặc có dấu để tránh cắt nhầm tên địa điểm
        // (bỏ "an", "di", "uong", "vieng" không dấu — dễ match "An Nhiên", "Di tích", v.v.)
        String regex = "(?i)^(tham quan|check[- ]in|checkin|an trua|an toi|an sang|uong cafe|binh minh tai|san may|tha den hoa dang|mua sam tai|mua sam|trai nghiem|ngam hoang hon|trekking|loi bo|dao bo|thuong thuc|check in|uong cà phe|uong ca phe|an trưa|an tối|an sáng|ăn trưa|ăn tối|ăn sáng|ăn|uống cafe|uống cà phê|uống ca phe|uống|bình minh tại|săn mây|đi|thả đèn hoa đăng|mua sắm tại|mua sắm|viếng|trải nghiệm|ngắm hoàng hôn|thưởng thức)\\s+";
        String cleaned = name.replaceAll(regex, "").trim();
        if (cleaned.length() > 0) {
            cleaned = Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
        }
        return cleaned;
    }

    /** Cùng phép chuẩn hóa với cache key của resolvePlace — dùng cho dedup trước fan-out. */
    @Override
    public String canonicalKey(String searchQuery) {
        return normalize(cleanPlaceName(searchQuery));
    }

    /**
     * Geocode anchor (destination/departure) qua Goong, cache theo tên. Sanity check:
     * địa chỉ trả về phải chứa tên gốc — geocode sai lặng lẽ sẽ làm hỏng validation cả trip.
     */
    @Override
    public Optional<GeoAnchor> geocodeAnchor(String locationName) {
        if (locationName == null || locationName.isBlank()) return Optional.empty();
        String key = normalize(locationName);
        GeoAnchor cached = anchorCache.get(key);
        if (cached != null) return Optional.of(cached);

        Optional<GoongClient.GeocodeResult> geoOpt =
                goongClient.forwardGeocode(PlaceQueryUtil.buildPlaceQuery(locationName.trim()));
        if (geoOpt.isEmpty()) {
            log.warn("Anchor geocode fail: '{}' — validation degrade về so chuỗi địa chỉ", locationName);
            return Optional.empty();
        }
        GoongClient.GeocodeResult geo = geoOpt.get();
        if (!addressMatchesDestination(geo.formattedAddress(), key)) {
            log.warn("Anchor geocode mismatch: '{}' → address='{}' — bỏ anchor", locationName, geo.formattedAddress());
            return Optional.empty();
        }
        GeoAnchor anchor = new GeoAnchor(geo.lat(), geo.lng(), geo.provinceName());
        if (anchorCache.size() < ANCHOR_CACHE_MAX) anchorCache.put(key, anchor);
        return Optional.of(anchor);
    }

    /**
     * NOT_SUPPORTED: không mở tx bao quanh Goong/SerpApi HTTP calls.
     * Việc save PlaceCache đã mở tx riêng qua PlaceCacheService.save() (REQUIRES_NEW).
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<PlaceCache> resolvePlace(String placeName, String destination,
                                             List<GeoAnchor> anchors, Instant deadline,
                                             boolean preferSerpIdentity, EnrichmentDepth depth, boolean forceRefresh) {
        if (placeName == null || placeName.isBlank()) return Optional.empty();

        String cleanedName = cleanPlaceName(placeName);
        String normalized = normalize(cleanedName);
        String normalizedDestination = normalize(destination);

        // 1. Cache theo cặp (normalizedName, normalizedDestination).
        // Lưu ý: row BASIC (serpEnriched=false, serpSyncedAt=null) KHÔNG được isFresh() coi là hit
        // → lần resolve FULL nền sau sẽ tự re-resolve và nâng lên FULL (xem PlaceCacheServiceImpl.isFresh).
        // forceRefresh (retry-pass sau 429): bỏ qua cache-hit để ép gọi lại bất kể backoff.
        if (!forceRefresh) {
            Optional<PlaceCache> fresh = placeCacheService.findFreshCache(normalized, normalizedDestination);
            if (fresh.isPresent()) {
                log.debug("Place cache hit: name='{}', dest='{}'", normalized, normalizedDestination);
                return fresh;
            }
        }

        if (pastDeadline(deadline)) return Optional.empty();

        // Hướng 2: POI cơ sở kinh doanh (nhà hàng/điểm tham quan) → SerpApi định danh chính xác hơn Goong.
        // Resolve qua SerpApi trước; nếu tìm được địa điểm thật (có GPS, khớp vùng) thì dùng luôn,
        // tránh việc Goong gom nhiều tên khác nhau về cùng 1 toạ độ vùng → trùng địa điểm.
        if (preferSerpIdentity) {
            Optional<PlaceCache> viaSerp = resolvePlaceViaSerpApi(cleanedName, normalized, normalizedDestination,
                    destination, anchors, deadline, depth);
            if (viaSerp.isPresent()) return viaSerp;
            if (pastDeadline(deadline)) return Optional.empty();
            log.debug("SerpApi-first miss cho '{}' — rơi về Goong", cleanedName);
        }

        // 2. Goong geocode — ưu tiên để có lat/lng chính xác; SerpApi là fallback
        String geocodeQuery = PlaceQueryUtil.buildPlaceQuery(cleanedName);
        Optional<GoongClient.GeocodeResult> geoOpt = goongClient.forwardGeocode(geocodeQuery);
        if (geoOpt.isEmpty()) {
            log.debug("Goong geocode: không tìm thấy '{}', thử SerpApi fallback", geocodeQuery);
            return resolvePlaceViaSerpApi(cleanedName, normalized, normalizedDestination, destination,
                    anchors, deadline, depth);
        }

        GoongClient.GeocodeResult geo = geoOpt.get();

        // 2b. Verify vùng: address chứa destination HOẶC gần anchor HOẶC trùng province —
        // tránh lưu lat/lng tỉnh khác, nhưng không reject oan vùng ven (sân bay, thác ngoại ô)
        if (!matchesRegion(geo.formattedAddress(), geo.provinceName(), geo.lat(), geo.lng(),
                normalizedDestination, anchors)) {
            log.warn("Goong mismatch: query='{}', address='{}', expectedDestination='{}' — thử SerpApi fallback",
                    geocodeQuery, geo.formattedAddress(), destination);
            return resolvePlaceViaSerpApi(cleanedName, normalized, normalizedDestination, destination,
                    anchors, deadline, depth);
        }

        // 3. Dedup theo goongPlaceId (an toàn với duplicate trong DB).
        // Refresh lastSyncedAt rồi save — nếu không, row stale TTL sẽ miss cache mãi mãi
        // và mỗi lần generate lại tốn 1 Goong call cho cùng địa điểm.
        if (geo.placeId() != null && !geo.placeId().isBlank()) {
            Optional<PlaceCache> byPlaceId = placeCacheRepository.findBestByGoongPlaceId(geo.placeId());
            if (byPlaceId.isPresent() && byPlaceId.get().isSerpEnriched()) {
                PlaceCache reused = byPlaceId.get();
                log.debug("Place cache dedup by goongPlaceId='{}', reuse id={}", geo.placeId(), reused.getId());
                return Optional.of(placeCacheService.refreshLastSyncedAt(reused, LocalDateTime.now()));
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
            // Row tái sử dụng giữ nguyên key gốc — re-key sang tên mới sẽ evict key cũ,
            // gây ping-pong giữa 2 searchQuery cùng trỏ về 1 goongPlaceId
            toSave = existing.get();
        } else {
            toSave = new PlaceCache();
            toSave.setNormalizedName(normalized);
            toSave.setNormalizedDestination(normalizedDestination);
            toSave.setName(cleanedName);
        }

        toSave.setLatitude(geo.lat());
        toSave.setLongitude(geo.lng());
        toSave.setGoongPlaceId(geo.placeId());
        toSave.setAddress(geo.formattedAddress());
        toSave.setProvinceName(geo.provinceName());
        toSave.setCommuneName(geo.communeName());
        toSave.setLastSyncedAt(LocalDateTime.now());

        // 5. SerpApi enrich (best-effort: nếu fail vẫn lưu data Goong).
        // BASIC: KHÔNG fetch ảnh/reviews — chỉ giữ toạ độ Goong. Để serpEnriched=false +
        // serpSyncedAt=null → isFresh() coi là miss → lần enrich nền (FULL) sẽ nâng cấp row này.
        if (depth == EnrichmentDepth.BASIC) {
            toSave.setSerpEnriched(false);
            toSave.setSerpSyncedAt(null);
        } else {
            // Nếu đã thử SerpApi-first ở trên mà miss (preferSerpIdentity=true rồi rơi xuống Goong) thì
            // KHÔNG search lại cùng query — tránh gọi SerpApi 2 lần (đốt quota, đẩy 429) cho 1 activity.
            SerpEnrichOutcome outcome = preferSerpIdentity
                    ? new SerpEnrichOutcome(false, LocalDateTime.now())
                    : enrichWithSerpApi(toSave, cleanedName, destination, normalizedDestination,
                            geo, anchors, deadline, placeName);
            toSave.setSerpEnriched(outcome.enriched());
            toSave.setSerpSyncedAt(outcome.syncedAt());
        }
        try {
            PlaceCache saved = placeCacheService.save(toSave);
            log.debug("Saved place cache id={} normalized='{}' dest='{}' depth={} serpEnriched={}",
                    saved.getId(), normalized, normalizedDestination, depth, saved.isSerpEnriched());
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
    public void enrichAccommodation(PlaceCache cache, LocalDate checkIn, LocalDate checkOut,
                                    Integer adults, Instant deadline) {
        if (cache == null) return;
        if (pastDeadline(deadline)) {
            log.debug("Hotel enrich skip (deadline): cache id={} name='{}'", cache.getId(), cache.getName());
            return;
        }
        try {
            Optional<SerpApiClient.HotelData> hotelOpt = serpApiClient.searchHotel(cache.getName(), checkIn, checkOut, adults);
            if (hotelOpt.isEmpty()) return;
            SerpApiClient.HotelData h = hotelOpt.get();

            // Search chỉ cho link Google Travel generic + rate tổng hợp. Gọi thêm Property Details
            // để lấy deep-link nhà cung cấp thật (ưu tiên Trip.com) + giá/đêm chính xác. Best-effort.
            String bookingLink = h.bookingLink();
            Long pricePerNight = h.pricePerNightVnd();
            if (h.propertyToken() != null && !h.propertyToken().isBlank()) {
                Optional<SerpApiClient.HotelBooking> detailsOpt =
                        serpApiClient.fetchHotelDetails(h.propertyToken(), checkIn, checkOut, adults);
                if (detailsOpt.isPresent()) {
                    SerpApiClient.HotelBooking d = detailsOpt.get();
                    if (d.bookingLink() != null && !d.bookingLink().isBlank()) bookingLink = d.bookingLink();
                    if (d.pricePerNightVnd() != null) pricePerNight = d.pricePerNightVnd();
                }
            }

            boolean changed = false;
            String bookingUrlToSave = null;
            Long pricePerNightToSave = null;
            BigDecimal ratingToSave = null;
            String photosJsonToSave = null;
            String reviewsJsonToSave = null;
            if (bookingLink != null && !bookingLink.isBlank()
                    && !bookingLink.equals(cache.getBookingUrl())) {
                cache.setBookingUrl(bookingLink);
                bookingUrlToSave = bookingLink;
                changed = true;
            }
            if (pricePerNight != null && !pricePerNight.equals(cache.getPricePerNightVnd())) {
                cache.setPricePerNightVnd(pricePerNight);
                pricePerNightToSave = pricePerNight;
                changed = true;
            }
            if (h.rating() != null && !h.rating().equals(cache.getRating())) {
                cache.setRating(h.rating());
                ratingToSave = h.rating();
                changed = true;
            }

            if (h.propertyToken() != null && !h.propertyToken().isBlank()) {
                // Ảnh/review hotel không phụ thuộc ngày — đã có trong cache thì không gọi lại (tiết kiệm quota)
                if (cache.getPhotosJson() == null && !pastDeadline(deadline)) {
                    List<Map<String, String>> photos = serpApiClient.fetchHotelPhotos(h.propertyToken());
                    if (photos != null && !photos.isEmpty()) {
                        try {
                            photosJsonToSave = objectMapper.writeValueAsString(photos);
                            cache.setPhotosJson(photosJsonToSave);
                            changed = true;
                        } catch (Exception ex) {
                            log.warn("Failed to serialize hotel photos: {}", ex.getMessage());
                        }
                    }
                }

                if (cache.getReviewsJson() == null && !pastDeadline(deadline)) {
                    List<Map<String, Object>> reviews = serpApiClient.fetchHotelReviews(h.propertyToken());
                    if (reviews != null && !reviews.isEmpty()) {
                        try {
                            reviewsJsonToSave = objectMapper.writeValueAsString(reviews);
                            cache.setReviewsJson(reviewsJsonToSave);
                            changed = true;
                        } catch (Exception ex) {
                            log.warn("Failed to serialize hotel reviews: {}", ex.getMessage());
                        }
                    }
                }
            }

            if (changed) {
                PlaceCache saved = placeCacheService.saveAccommodationEnrichment(cache,
                        bookingUrlToSave, pricePerNightToSave, ratingToSave, photosJsonToSave, reviewsJsonToSave);
                copyAccommodationEnrichment(cache, saved);
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
                                                List<GeoAnchor> anchors,
                                                Instant deadline,
                                                String originalName) {
        try {
            if (pastDeadline(deadline)) {
                log.debug("SerpApi enrich skip (deadline) cho '{}'", cleanedName);
                return new SerpEnrichOutcome(false, null);
            }
            // AI đã được instruct để đưa city vào searchQuery, không append destination trip nữa
            // (tránh "Sân bay Nội Bài Hà Nội Đà Lạt Việt Nam" — SerpApi tìm nhầm thành phố)
            String serpQuery = PlaceQueryUtil.buildPlaceQuery(cleanedName);
            List<SerpApiClient.PlaceData> candidates = serpApiClient.searchPlaceCandidates(serpQuery);
            if (candidates.isEmpty()) {
                log.debug("SerpApi không trả về candidate cho '{}', áp dụng backoff", serpQuery);
                return new SerpEnrichOutcome(false, LocalDateTime.now());
            }

            SerpApiClient.PlaceData s = pickBestCandidate(candidates, cleanedName, normalizedDestination, geo, anchors);
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
            if (photosId != null && !photosId.isBlank() && !pastDeadline(deadline)) {
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
            if (reviewsId != null && !reviewsId.isBlank() && !pastDeadline(deadline)) {
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
                                                      GoongClient.GeocodeResult geo,
                                                      List<GeoAnchor> anchors) {
        if (candidates.isEmpty()) return null;

        String normalizedClean = normalize(cleanedName);

        SerpApiClient.PlaceData best = null;
        double bestScore = -1;
        boolean bestRegionOk = false;

        for (SerpApiClient.PlaceData c : candidates) {
            boolean regionOk = addressMatchesDestination(c.address(), normalizedDestination)
                    || nearAnchor(c.latitude(), c.longitude(), anchors);
            double titleScore = titleSimilarity(normalize(c.title()), normalizedClean);
            double distanceScore = distanceScore(c, geo);

            // Score: ưu tiên đúng vùng (address match hoặc gần anchor) hơn title/distance
            double score = (regionOk ? 100.0 : 0.0) + titleScore * 10.0 + distanceScore;

            if (score > bestScore) {
                bestScore = score;
                best = c;
                bestRegionOk = regionOk;
            }
        }

        // Bắt buộc address khớp destination HOẶC GPS gần anchor, nếu destination biết được
        if (normalizedDestination != null && !normalizedDestination.isBlank() && !bestRegionOk) {
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
        String destinationAlias = normalizeRegionAlias(normalizedDestination);
        return normalizedAddress.contains(normalizedDestination)
                || (destinationAlias != null && !destinationAlias.isBlank()
                && normalizedAddress.contains(destinationAlias));
    }

    /**
     * Validate vùng cho kết quả Goong: address chứa destination (như cũ), HOẶC cách 1 anchor
     * ≤ ANCHOR_RADIUS_KM (cứu vùng ven: sân bay Liên Khương, thác ngoại ô...), HOẶC trùng
     * provinceName với anchor (cứu điểm cùng tỉnh nhưng xa: Bảo Lộc cách Đà Lạt 83km).
     * Anchors rỗng → degrade về so chuỗi địa chỉ (behavior cũ).
     */
    private boolean matchesRegion(String address, String provinceName, BigDecimal lat, BigDecimal lng,
                                  String normalizedDestination, List<GeoAnchor> anchors) {
        if (addressMatchesDestination(address, normalizedDestination)) return true;
        if (nearAnchor(lat, lng, anchors)) return true;
        if (provinceName != null && !provinceName.isBlank() && anchors != null) {
            String normalizedProvince = normalizeRegionAlias(normalize(provinceName));
            for (GeoAnchor a : anchors) {
                if (a != null && a.provinceName() != null
                        && normalizedProvince.equals(normalizeRegionAlias(normalize(a.provinceName())))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True nếu (lat,lng) cách 1 anchor bất kỳ ≤ ANCHOR_RADIUS_KM. */
    private boolean nearAnchor(BigDecimal lat, BigDecimal lng, List<GeoAnchor> anchors) {
        if (lat == null || lng == null || anchors == null) return false;
        for (GeoAnchor a : anchors) {
            if (a == null || a.lat() == null || a.lng() == null) continue;
            if (haversineKm(lat, lng, a.lat(), a.lng()) <= ANCHOR_RADIUS_KM) return true;
        }
        return false;
    }

    private boolean pastDeadline(Instant deadline) {
        return deadline != null && Instant.now().isAfter(deadline);
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

    private void copyAccommodationEnrichment(PlaceCache target, PlaceCache source) {
        if (target == null || source == null) return;
        target.setBookingUrl(source.getBookingUrl());
        target.setPricePerNightVnd(source.getPricePerNightVnd());
        target.setRating(source.getRating());
        target.setPhotosJson(source.getPhotosJson());
        target.setReviewsJson(source.getReviewsJson());
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

    private String normalizeRegionAlias(String normalizedName) {
        if (normalizedName == null) return null;
        String region = normalizedName
                .replaceFirst("^(thanh pho|tp|tinh)\\s+", "")
                .trim();
        return switch (region) {
            case "hcm", "tp hcm", "tphcm", "sai gon" -> "ho chi minh";
            default -> region;
        };
    }

    private String buildPhotosJson(String thumbnailUrl) {
        try {
            return objectMapper.writeValueAsString(List.of(Map.of("url", thumbnailUrl, "thumbnail", thumbnailUrl)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve địa điểm bằng SerpApi Google Maps (lat/lng + enrich). Dùng cho 2 trường hợp:
     * (a) Goong không geocode được / trả sai vùng (fallback); (b) POI cơ sở kinh doanh cần
     * SerpApi định danh chính xác (preferSerpIdentity). Chỉ lưu nếu candidate có GPS và khớp vùng.
     */
    private Optional<PlaceCache> resolvePlaceViaSerpApi(
            String cleanedName, String normalized, String normalizedDestination, String destination,
            List<GeoAnchor> anchors, Instant deadline, EnrichmentDepth depth) {

        if (pastDeadline(deadline)) return Optional.empty();

        String serpQuery = PlaceQueryUtil.buildPlaceQuery(cleanedName);
        List<SerpApiClient.PlaceData> candidates = serpApiClient.searchPlaceCandidates(serpQuery);
        if (candidates.isEmpty()) {
            log.debug("SerpApi fallback: không tìm thấy '{}'", serpQuery);
            return Optional.empty();
        }

        SerpApiClient.PlaceData best = pickBestCandidate(candidates, cleanedName, normalizedDestination, null, anchors);
        if (best == null) {
            log.warn("SerpApi fallback: không có candidate khớp destination='{}' cho query='{}'",
                    destination, serpQuery);
            return Optional.empty();
        }
        if (best.latitude() == null || best.longitude() == null) {
            log.debug("SerpApi fallback: candidate '{}' không có GPS — bỏ qua", best.title());
            return Optional.empty();
        }

        Optional<PlaceCache> existing = (normalizedDestination == null || normalizedDestination.isBlank())
                ? placeCacheRepository.findFirstByNormalizedNameAndNormalizedDestinationIsNull(normalized)
                : placeCacheRepository.findFirstByNormalizedNameAndNormalizedDestination(normalized, normalizedDestination);

        PlaceCache toSave = existing.orElse(new PlaceCache());
        toSave.setNormalizedName(normalized);
        toSave.setNormalizedDestination(normalizedDestination);
        if (toSave.getName() == null || toSave.getName().isBlank()) toSave.setName(cleanedName);
        toSave.setLatitude(best.latitude());
        toSave.setLongitude(best.longitude());
        toSave.setAddress(best.address());
        // Tọa độ giờ là của SerpApi — clear định danh Goong cũ (vừa bị miss/reject) để row
        // không thành "lai 2 nguồn": Activity.placeId/geocodingProvider sẽ phản ánh đúng serpapi
        toSave.setGoongPlaceId(null);
        toSave.setProvinceName(null);
        toSave.setCommuneName(null);
        toSave.setLastSyncedAt(LocalDateTime.now());

        toSave.setSerpDataId(best.dataId());
        toSave.setSerpPlaceId(best.placeId());
        toSave.setSerpTitle(best.title());
        toSave.setPlaceType(best.type());
        toSave.setRating(best.rating());
        toSave.setReviewCount(best.reviewCount());
        toSave.setOpeningHoursJson(best.operatingHoursJson());
        toSave.setOpenState(resolveOpenState(best));
        String hoursText = best.hours();
        if (hoursText == null && best.openState() != null && !best.openState().isBlank()) {
            String raw = best.openState();
            hoursText = raw.length() > 255 ? raw.substring(0, 255) : raw;
        }
        toSave.setHoursText(hoursText);
        toSave.setPhone(best.phone());
        toSave.setWebsite(best.website());

        if (depth == EnrichmentDepth.BASIC) {
            // BASIC: chỉ giữ thumbnail (1 ảnh) cho card hiển thị ngay, KHÔNG fetch gallery/reviews.
            // serpEnriched=false + serpSyncedAt=null → enrich nền (FULL) sẽ nâng cấp row này.
            if (toSave.getPhotosJson() == null && best.thumbnailUrl() != null) {
                toSave.setPhotosJson(buildPhotosJson(best.thumbnailUrl()));
            }
            toSave.setSerpEnriched(false);
            toSave.setSerpSyncedAt(null);
            log.info("SerpApi BASIC resolved: name='{}' lat={} lng={} rating={} (ảnh/review để enrich nền)",
                    cleanedName, best.latitude(), best.longitude(), best.rating());
        } else {
            String dataId = best.dataId() != null ? best.dataId() : best.placeId();
            if (dataId != null && !dataId.isBlank() && !pastDeadline(deadline)) {
                List<Map<String, String>> photos = safeFetchPhotos(dataId);
                if (!photos.isEmpty()) {
                    try { toSave.setPhotosJson(objectMapper.writeValueAsString(photos)); }
                    catch (Exception ex) { log.warn("SerpApi fallback: serialize photos failed: {}", ex.getMessage()); }
                }
                if (toSave.getPhotosJson() == null && best.thumbnailUrl() != null) {
                    toSave.setPhotosJson(buildPhotosJson(best.thumbnailUrl()));
                }

                List<Map<String, Object>> reviews = pastDeadline(deadline) ? List.of() : safeFetchReviews(dataId);
                if (!reviews.isEmpty()) {
                    try { toSave.setReviewsJson(objectMapper.writeValueAsString(reviews)); }
                    catch (Exception ex) { log.warn("SerpApi fallback: serialize reviews failed: {}", ex.getMessage()); }
                }
            } else if (best.thumbnailUrl() != null) {
                toSave.setPhotosJson(buildPhotosJson(best.thumbnailUrl()));
            }

            boolean hasBasic = best.rating() != null || best.reviewCount() != null
                    || best.phone() != null || best.website() != null;
            boolean hasPhotos = toSave.getPhotosJson() != null;
            boolean hasOpeningHours = best.operatingHoursJson() != null || best.hours() != null || best.openState() != null;
            boolean hasReviews = toSave.getReviewsJson() != null;
            toSave.setSerpEnriched(hasBasic && hasPhotos && (hasOpeningHours || hasReviews));
            toSave.setSerpSyncedAt(LocalDateTime.now());

            log.info("SerpApi fallback resolved (Goong miss): name='{}' lat={} lng={} rating={} serpEnriched={}",
                    cleanedName, best.latitude(), best.longitude(), best.rating(), toSave.isSerpEnriched());
        }

        try {
            return Optional.of(placeCacheService.save(toSave));
        } catch (DataIntegrityViolationException ex) {
            log.warn("SerpApi fallback: duplicate on save for '{}' — refetching", normalized);
            return (normalizedDestination == null || normalizedDestination.isBlank())
                    ? placeCacheRepository.findFirstByNormalizedNameAndNormalizedDestinationIsNull(normalized)
                    : placeCacheRepository.findFirstByNormalizedNameAndNormalizedDestination(normalized, normalizedDestination);
        }
    }
}
