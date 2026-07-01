package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.common.enums.TripLifecycleStatus;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.dto.AiCallResult;
import com.tranbac.chiptripbe.module.ai.dto.AiItineraryResult;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.ai.service.AiService;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceEnrichmentService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.ShareTokenResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerateResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripMemberResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripSummaryResponse;
import com.tranbac.chiptripbe.module.trip.entity.Activity;
import com.tranbac.chiptripbe.module.trip.entity.ChecklistItem;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import com.tranbac.chiptripbe.module.trip.repository.ActivityAlternativeSessionRepository;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.ChecklistItemRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripCommentRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripLikeRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripMemberRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripGenerationPersistenceService;
import com.tranbac.chiptripbe.module.trip.service.TripMemberService;
import com.tranbac.chiptripbe.module.trip.service.TripService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import com.tranbac.chiptripbe.module.user.service.EntitlementPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TripServiceImpl implements TripService {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Chờ giữa pass1 và retry-pass enrich nền để rate window SerpApi dịu sau 429 (best-effort). */
    private static final long BACKGROUND_RETRY_DELAY_MS = 5_000;

    /**
     * Cửa sổ coi 1 place chưa-FULL là "đang enrich nền" — tính từ lastSyncedAt (lần cuối đụng row).
     * Bao trùm cả pass1 + retry-pass nền (vài chục giây) để cờ enriching giữ true xuyên suốt,
     * đồng thời CÓ TRẦN để cờ không kẹt true vĩnh viễn nếu 1 row không bao giờ được nâng FULL.
     */
    private static final Duration ENRICHING_GRACE = Duration.ofMinutes(5);

    private final TripRepository tripRepository;
    private final TripDayRepository tripDayRepository;
    private final ActivityRepository activityRepository;
    private final ActivityAlternativeSessionRepository activityAlternativeSessionRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final TripMemberRepository tripMemberRepository;
    private final TripLikeRepository tripLikeRepository;
    private final TripCommentRepository tripCommentRepository;
    private final TripMemberService tripMemberService;
    private final UserRepository userRepository;
    private final AiService aiService;
    private final AiUsageRepository aiUsageRepository;
    private final ObjectMapper objectMapper;
    private final PlaceEnrichmentService placeEnrichmentService;
    private final PlaceCacheRepository placeCacheRepository;
    private final TripGenerationPersistenceService tripGenerationPersistenceService;
    @Qualifier("enrichmentExecutor")
    private final Executor enrichmentExecutor;
    @Qualifier("backgroundEnrichmentExecutor")
    private final Executor backgroundEnrichmentExecutor;

    /**
     * Orchestration only — KHÔNG annotate @Transactional.
     * Tránh giữ DB transaction trong khi gọi Gemini và resolve place (HTTP calls dài).
     *
     * Flow:
     * 1. Validate request + read-only check user/credits (mỗi findById tự mở tx ngắn).
     * 2. Gọi AI ngoài transaction.
     * 3. Validate AI output (fail-fast nếu invalid — không tốn lượt AI, không persist trip rác).
     * 4. Resolve place (mỗi resolvePlace tự mở tx ngắn để upsert PlaceCache).
     * 5. Persist trip + days + activities + checklist + AiUsage trong 1 tx ngắn.
     *
     * propagation = NOT_SUPPORTED để override class-level @Transactional(readOnly=true) —
     * tránh giữ DB connection trong khi gọi Gemini.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TripGenerateResponse generateTrip(Long userId, GenerateTripRequest request) {
        String userPreferences = validateRequestAndCredits(userId, request);

        AiCallResult aiCallResult = aiService.generateItinerary(request, userPreferences);

        // Ngày 1 enrich FULL đồng bộ; ngày 2..N chỉ BASIC (toạ độ) để trả response nhanh.
        ResolveOutcome outcome = resolvePlaces(aiCallResult.itinerary(), request);

        TripGenerateResponse response = tripGenerationPersistenceService.persistGeneratedTrip(
                userId, request, aiCallResult, outcome.resolved());

        // Sau khi trip đã COMMIT: nâng các địa điểm ngày 2..N lên FULL ở luồng nền (best-effort).
        // FE poll GET /trips/{id} sẽ tự thấy ảnh/review nảy ra dần (đọc PlaceCache live).
        if (outcome.backgroundJob() != null) {
            try {
                backgroundEnrichmentExecutor.execute(outcome.backgroundJob());
            } catch (Exception e) {
                // Executor từ chối (queue đầy) → bỏ qua, địa điểm giữ mức BASIC vẫn dùng được.
                log.warn("Background enrich không thể submit cho trip id={}: {}", response.getId(), e.getMessage());
            }
        }
        return response;
    }

    @Override
    public void validateGenerateRequest(Long userId, GenerateTripRequest request) {
        // Tái dùng đúng các kiểm tra fail-fast của luồng sync (không trừ credit ở đây).
        validateRequestAndCredits(userId, request);
    }

    /**
     * Read-only, TRƯỚC khi gọi Gemini (fail-fast). KHÔNG trừ credit ở đây — nguồn chân lý là
     * lần deduct atomic trong persist (CREDIT_PREMIUM_SPEC.md Mục 5.5).
     *
     * @return User.preferences (gu du lịch đã lưu) để cá nhân hóa prompt AI — null nếu chưa có.
     */
    private String validateRequestAndCredits(Long userId, GenerateTripRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));

        boolean premiumNow = user.isPremium();

        // 1) Giới hạn theo tier (BE bắt buộc — không tin FE)
        long tripDays = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        int maxDays = EntitlementPolicy.maxTripDays(premiumNow);
        if (tripDays > maxDays) {
            throw AppException.limitExceeded(
                    premiumNow
                            ? "Lịch trình tối đa " + maxDays + " ngày."
                            : "Tài khoản thường chỉ tạo lịch trình tối đa " + maxDays + " ngày.",
                    "PREMIUM");
        }
        int styleCount = request.getStyles() != null ? request.getStyles().size() : 0;
        int maxStyles = EntitlementPolicy.maxStyles(premiumNow);
        if (styleCount > maxStyles) {
            throw AppException.limitExceeded(
                    "Tài khoản thường chỉ chọn tối đa " + maxStyles + " phong cách.", "PREMIUM");
        }

        // 2) Có credit để generate không (fail-fast, không trừ ở đây)
        int effectiveTrial = today().equals(user.getTrialCreditDate()) ? user.getTrialCreditBalance() : 1;
        boolean hasCredit = user.effectiveAiCreditUnits() >= 100 || effectiveTrial >= 1;
        if (!hasCredit) {
            throw AppException.dailyTrialUsed();   // 402
        }

        // 3) Validate ngày (bug #4: không cho ngày quá khứ)
        LocalDate today = today();
        if (request.getStartDate().isBefore(today)) {
            throw AppException.badRequest("Ngày bắt đầu không được trước ngày hôm nay");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw AppException.badRequest("Ngày kết thúc không được trước ngày bắt đầu");
        }
        return user.getPreferences();
    }

    /** Ngày hiện tại theo giờ VN (reset trial & validate ngày theo Asia/Ho_Chi_Minh, không UTC). */
    private LocalDate today() {
        return LocalDate.now(VN_ZONE);
    }

    /** Activity geocodable kèm ngày (dayNumber: số thứ tự ngày AI sinh, ≥1) và date của AiDay. */
    private record GeoActivity(AiItineraryResult.AiActivity act, LocalDate date, int dayNumber) {}

    /**
     * Kết quả resolvePlaces: map place đã resolve (cho persist) + job enrich FULL ngầm cho ngày 2..N
     * (chạy SAU khi trip commit). backgroundJob == null nếu không có ngày nào cần enrich nền.
     */
    private record ResolveOutcome(Map<AiItineraryResult.AiActivity, PlaceCache> resolved, Runnable backgroundJob) {}

    /** Mô tả 1 nhóm địa điểm cần enrich FULL ở luồng nền. */
    private record BgEnrich(String searchQuery, String destination,
                            List<PlaceEnrichmentService.GeoAnchor> anchors, boolean preferSerp, int minDay) {}

    /**
     * Thu thập tất cả geocodable activities từ itinerary rồi resolve song song.
     * Fail-soft: 1 activity resolve fail không làm fail toàn bộ generate trip.
     */
    private ResolveOutcome resolvePlaces(
            AiItineraryResult itinerary, GenerateTripRequest request) {
        if (itinerary.getDays() == null) return new ResolveOutcome(new ConcurrentHashMap<>(), null);

        List<GeoActivity> geocodable = new ArrayList<>();
        int dayIndex = 0;
        for (AiItineraryResult.AiDay day : itinerary.getDays()) {
            dayIndex++;
            if (day.getActivities() == null) continue;
            LocalDate dayDate;
            try {
                dayDate = LocalDate.parse(day.getDate());
            } catch (Exception e) {
                dayDate = null;
            }
            int dayNumber = day.getDayNumber() != null ? day.getDayNumber() : dayIndex;
            for (AiItineraryResult.AiActivity act : day.getActivities()) {
                ActivityType type = parseActivityType(act.getType());
                if (!shouldGeocode(type)) continue;
                if (act.getSearchQuery() == null || act.getSearchQuery().isBlank()) continue;
                geocodable.add(new GeoActivity(act, dayDate, dayNumber));
            }
        }

        return resolveAllPlacesParallel(
                geocodable,
                request.getDestination(),
                request.getDeparture(),
                request.getStartDate(),
                request.getEndDate(),
                request.getPeopleCount());
    }

    private ResolveOutcome resolveAllPlacesParallel(
            List<GeoActivity> activities,
            String destination,
            String departure,
            LocalDate tripStart,
            LocalDate tripEnd,
            Integer adults) {
        Map<AiItineraryResult.AiActivity, PlaceCache> result = new ConcurrentHashMap<>();
        if (activities.isEmpty()) return new ResolveOutcome(result, null);

        // Anchor GPS validate vùng (fail-soft: anchor rỗng → so chuỗi địa chỉ như cũ).
        // anchorDep chỉ dùng cho TRANSPORT — phương tiện đầu đi nằm ở thành phố xuất phát.
        PlaceEnrichmentService.GeoAnchor anchorDest =
                placeEnrichmentService.geocodeAnchor(destination).orElse(null);
        PlaceEnrichmentService.GeoAnchor anchorDep =
                placeEnrichmentService.geocodeAnchor(departure).orElse(null);

        // Dedup theo canonical key (cùng phép chuẩn hóa với cache key của resolvePlace) TRƯỚC
        // fan-out: khách sạn N đêm / query khác chuỗi nhưng cùng địa điểm chỉ resolve + enrich
        // 1 lần, mọi activity trong group share cùng instance PlaceCache (giá nhất quán)
        Map<String, List<GeoActivity>> groups = new LinkedHashMap<>();
        for (GeoActivity ga : activities) {
            groups.computeIfAbsent(placeEnrichmentService.canonicalKey(ga.act().getSearchQuery()),
                    k -> new ArrayList<>()).add(ga);
        }

        // Deadline chung: quá hạn thì resolvePlace tự bỏ các external call còn lại —
        // tránh đốt quota Goong/SerpApi sau khi orTimeout(60s) đã nổ và trip đã persist
        Instant deadline = Instant.now().plusSeconds(55);

        List<List<GeoActivity>> groupList = List.copyOf(groups.values());
        // Nhóm chỉ-BASIC ở luồng đồng bộ (ngày 2..N, không phải khách sạn) → enrich FULL ngầm sau.
        List<BgEnrich> bgList = new ArrayList<>();
        List<CompletableFuture<PlaceCache>> futures = new ArrayList<>(groupList.size());
        for (List<GeoActivity> group : groupList) {
            AiItineraryResult.AiActivity first = group.get(0).act();
            boolean hasTransport = group.stream()
                    .anyMatch(g -> parseActivityType(g.act().getType()) == ActivityType.TRANSPORT);
            boolean hasAccommodation = group.stream()
                    .anyMatch(g -> parseActivityType(g.act().getType()) == ActivityType.ACCOMMODATION);
            // Hướng 2: POI cơ sở kinh doanh (FOOD/ATTRACTION) ưu tiên định danh qua SerpApi —
            // tránh Goong gom nhiều nhà hàng/điểm khác nhau về cùng 1 toạ độ vùng.
            // TRANSPORT/ACCOMMODATION giữ Goong-first (Goong tốt cho sân bay/ga; hotel có luồng enrich riêng).
            boolean hasNamedPoi = group.stream().anyMatch(g -> {
                ActivityType t = parseActivityType(g.act().getType());
                return t == ActivityType.FOOD || t == ActivityType.ATTRACTION;
            });
            boolean preferSerp = hasNamedPoi && !hasTransport && !hasAccommodation;
            List<PlaceEnrichmentService.GeoAnchor> anchors = new ArrayList<>(2);
            if (anchorDest != null) anchors.add(anchorDest);
            if (hasTransport && anchorDep != null) anchors.add(anchorDep);

            // Ngày 1 → FULL ngay (user thấy đẹp lập tức). Ngày 2..N → BASIC (toạ độ), enrich FULL ngầm sau.
            // Khách sạn LUÔN FULL đồng bộ: giá/đặt phòng cần khoá vào Activity.costVnd lúc persist.
            int minDay = group.stream().mapToInt(GeoActivity::dayNumber).min().orElse(1);
            PlaceEnrichmentService.EnrichmentDepth depth =
                    (minDay >= 2 && !hasAccommodation)
                            ? PlaceEnrichmentService.EnrichmentDepth.BASIC
                            : PlaceEnrichmentService.EnrichmentDepth.FULL;
            if (depth == PlaceEnrichmentService.EnrichmentDepth.BASIC) {
                bgList.add(new BgEnrich(first.getSearchQuery(), destination, List.copyOf(anchors), preferSerp, minDay));
            }

            final boolean fHasAccommodation = hasAccommodation;
            final boolean fPreferSerp = preferSerp;
            final List<PlaceEnrichmentService.GeoAnchor> fAnchors = anchors;
            final PlaceEnrichmentService.EnrichmentDepth fDepth = depth;
            final List<GeoActivity> fGroup = group;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    Optional<PlaceCache> placeOpt = placeEnrichmentService.resolvePlace(
                            first.getSearchQuery(), destination, fAnchors, deadline, fPreferSerp, fDepth);
                    placeOpt.ifPresent(cache -> {
                        if (fHasAccommodation) {
                            // Check-in/out theo đúng dải đêm của khách sạn này (trip có thể
                            // đổi khách sạn giữa chừng) — fallback range cả trip nếu thiếu date
                            LocalDate checkIn = fGroup.stream().map(GeoActivity::date)
                                    .filter(Objects::nonNull).min(LocalDate::compareTo).orElse(tripStart);
                            LocalDate checkOut = fGroup.stream().map(GeoActivity::date)
                                    .filter(Objects::nonNull).max(LocalDate::compareTo)
                                    .map(d -> d.plusDays(1)).orElse(tripEnd);
                            placeEnrichmentService.enrichAccommodation(cache, checkIn, checkOut, adults, deadline);
                        }
                    });
                    return placeOpt.orElse(null);
                } catch (Exception e) {
                    log.warn("Resolve place failed (skip): activity='{}', searchQuery='{}', error={}",
                            first.getName(), first.getSearchQuery(), e.getMessage());
                    return null;
                }
            }, enrichmentExecutor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(60, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("Parallel enrichment timeout/error: {}", ex.getMessage());
                    return null;
                })
                .join();

        // Populate sau join, chỉ từ future đã hoàn tất — task bị orTimeout bỏ rơi không thể
        // ghi xen vào map trong lúc persist đang đọc; mỗi group nguyên tử (có place cả nhóm)
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<PlaceCache> f = futures.get(i);
            if (!f.isDone() || f.isCompletedExceptionally()) continue;
            PlaceCache cache = f.getNow(null);
            if (cache == null) continue;
            for (GeoActivity ga : groupList.get(i)) {
                result.put(ga.act(), cache);
            }
        }

        return new ResolveOutcome(result, buildBackgroundEnrichJob(bgList));
    }

    /**
     * Job enrich FULL ngầm cho các nhóm BASIC (ngày 2..N), chạy tuần tự theo thứ tự ngày trên
     * backgroundEnrichmentExecutor (1 thread → nhường foreground ở token-bucket SerpApi).
     * resolvePlace(FULL) với deadline=null: row BASIC bị isFresh() coi là miss → re-resolve nâng FULL,
     * cập nhật cùng PlaceCache mà Activity đang trỏ tới → FE poll thấy ảnh/review nảy ra dần.
     * Trả null nếu không có gì để enrich nền.
     */
    private Runnable buildBackgroundEnrichJob(List<BgEnrich> bgList) {
        if (bgList.isEmpty()) return null;
        bgList.sort(Comparator.comparingInt(BgEnrich::minDay));
        List<BgEnrich> jobs = List.copyOf(bgList);
        return () -> {
            log.info("Background FULL enrich start: {} place(s)", jobs.size());
            List<BgEnrich> incomplete = new ArrayList<>();
            int ok = 0;
            for (BgEnrich b : jobs) {
                if (enrichOnce(b, false)) ok++; else incomplete.add(b);
            }
            log.info("Background FULL enrich pass1: {}/{} đủ, {} cần retry", ok, jobs.size(), incomplete.size());

            // Retry-pass: place chưa đủ (serpEnriched=false) — thường do 429 ở fetch reviews/photos.
            // Chờ rate window dịu rồi enrich lại với forceRefresh=true (bỏ qua backoff, nếu không
            // findFreshCache sẽ trả lại chính row chưa đủ đó). Best-effort: 429 lần nữa thì thôi
            // (refresh thụ động theo backoff vẫn còn). Place không có data thật vẫn false → bỏ qua sau retry.
            if (incomplete.isEmpty()) {
                log.info("Background FULL enrich done: tất cả đủ ở pass1");
                return;
            }
            try {
                Thread.sleep(BACKGROUND_RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            int recovered = 0;
            for (BgEnrich b : incomplete) {
                if (enrichOnce(b, true)) recovered++;
            }
            log.info("Background FULL enrich retry: {}/{} hồi phục sau 429/thiếu data", recovered, incomplete.size());
        };
    }

    /** 1 lần enrich FULL nền cho 1 nhóm; trả true nếu row kết quả đã serpEnriched. fail-soft. */
    private boolean enrichOnce(BgEnrich b, boolean forceRefresh) {
        try {
            Optional<PlaceCache> r = placeEnrichmentService.resolvePlace(
                    b.searchQuery(), b.destination(), b.anchors(), null, b.preferSerp(),
                    PlaceEnrichmentService.EnrichmentDepth.FULL, forceRefresh);
            return r.isPresent() && r.get().isSerpEnriched();
        } catch (Exception e) {
            log.warn("Background FULL enrich {} failed for '{}': {}",
                    forceRefresh ? "retry" : "pass1", b.searchQuery(), e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TripSummaryResponse> getMyTrips(Long userId, Pageable pageable) {
        Page<Trip> page = tripRepository.findByUserId(userId, pageable);
        List<Long> tripIds = page.getContent().stream().map(Trip::getId).collect(java.util.stream.Collectors.toList());
        Map<Long, String> imageUrlMap = new HashMap<>();
        if (!tripIds.isEmpty()) {
            List<Object[]> rows = activityRepository.findFirstImageUrlsForTrips(tripIds);
            for (Object[] row : rows) {
                Long tripId = row[0] instanceof Number ? ((Number) row[0]).longValue() : Long.parseLong(row[0].toString());
                String imgUrl = row[1] != null ? row[1].toString() : null;
                imageUrlMap.putIfAbsent(tripId, imgUrl);
            }
        }
        return page.map(trip -> toSummaryResponse(trip, imageUrlMap.get(trip.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public TripDetailResponse getTripDetail(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        return buildTripDetailResponse(trip);
    }

    @Override
    @Transactional
    public TripDetailResponse updateTrip(Long userId, Long tripId, UpdateTripRequest request) {
        Trip trip = findTripByIdAndUserId(tripId, userId);

        if (request.getTitle() != null) {
            trip.setTitle(request.getTitle());
        }
        if (request.getDateStart() != null) {
            trip.setDateStart(request.getDateStart());
        }
        if (request.getDateEnd() != null) {
            trip.setDateEnd(request.getDateEnd());
        }
        if (request.getPeopleCount() != null) {
            trip.setPeopleCount(request.getPeopleCount());
        }
        if (request.getBudgetVnd() != null) {
            trip.setBudgetVnd(request.getBudgetVnd());
        }
        if (request.getStyles() != null) {
            try {
                trip.setStyles(objectMapper.writeValueAsString(request.getStyles()));
            } catch (JsonProcessingException e) {
                // ignore
            }
        }

        trip = tripRepository.save(trip);
        log.info("Updated trip id={} by userId={}", tripId, userId);
        return buildTripDetailResponse(trip);
    }

    @Override
    @Transactional
    public void deleteTrip(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        aiUsageRepository.nullifyTripReference(tripId);
        // likes/comments dùng cột tripId Long thuần (không FK cascade) — phải cleanup tay
        activityAlternativeSessionRepository.deleteByTripId(tripId);
        tripLikeRepository.deleteByTripId(tripId);
        tripCommentRepository.deleteByTripId(tripId);
        tripRepository.delete(trip);
        log.info("Deleted trip id={} by userId={}", tripId, userId);
    }

    @Override
    @Transactional
    public TripDetailResponse cloneTrip(Long userId, Long tripId) {
        Trip original = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        // Cho phép clone khi là chủ trip HOẶC trip đang công khai (Khám phá)
        boolean isOwner = original.getUser().getId().equals(userId);
        if (!isOwner && !original.isPublic()) {
            throw AppException.forbidden("Bạn không có quyền với chuyến đi này");
        }
        User cloner = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));

        // Bản sao là trip riêng tư mới của người clone — KHÔNG kế thừa isPublic/shareToken/
        // inviteToken/likesCount/commentsCount (builder không set → mặc định false/null/0)
        Trip clone = Trip.builder()
                .user(cloner)
                .title(original.getTitle() + " (Copy)")
                .departure(original.getDeparture())
                .destination(original.getDestination())
                .dateStart(original.getDateStart())
                .dateEnd(original.getDateEnd())
                .peopleCount(original.getPeopleCount())
                .budgetVnd(original.getBudgetVnd())
                .styles(original.getStyles())
                .build();
        clone = tripRepository.save(clone);
        tripMemberService.seedOwner(clone, cloner);

        // Clone days and activities
        for (TripDay originalDay : original.getDays()) {
            TripDay cloneDay = TripDay.builder()
                    .trip(clone)
                    .dayNumber(originalDay.getDayNumber())
                    .date(originalDay.getDate())
                    .build();
            cloneDay = tripDayRepository.save(cloneDay);

            for (Activity originalActivity : originalDay.getActivities()) {
                Activity cloneActivity = Activity.builder()
                        .day(cloneDay)
                        .startTime(originalActivity.getStartTime())
                        .name(originalActivity.getName())
                        .description(originalActivity.getDescription())
                        .type(originalActivity.getType())
                        .costVnd(originalActivity.getCostVnd())
                        .searchQuery(originalActivity.getSearchQuery())
                        .latitude(originalActivity.getLatitude())
                        .longitude(originalActivity.getLongitude())
                        .placeId(originalActivity.getPlaceId())
                        .formattedAddress(originalActivity.getFormattedAddress())
                        .geocodingProvider(originalActivity.getGeocodingProvider())
                        .placeCacheId(originalActivity.getPlaceCacheId())
                        .imageUrl(originalActivity.getImageUrl())
                        .bookingUrl(originalActivity.getBookingUrl())
                        .displayOrder(originalActivity.getDisplayOrder())
                        .build();
                activityRepository.save(cloneActivity);
            }
        }

        // Clone checklist
        for (ChecklistItem originalItem : original.getChecklist()) {
            ChecklistItem cloneItem = ChecklistItem.builder()
                    .trip(clone)
                    .category(originalItem.getCategory())
                    .name(originalItem.getName())
                    .isChecked(false)
                    .displayOrder(originalItem.getDisplayOrder())
                    .build();
            checklistItemRepository.save(cloneItem);
        }

        log.info("Cloned trip id={} to new trip id={} by userId={}", tripId, clone.getId(), userId);
        return buildTripDetailResponse(clone);
    }

    @Override
    @Transactional
    public ShareTokenResponse enableShare(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        String token = UUID.randomUUID().toString().replace("-", "");
        trip.setShareToken(token);
        tripRepository.save(trip);
        log.info("Enabled share for trip id={}, token={}", tripId, token);
        return ShareTokenResponse.builder().shareToken(token).build();
    }

    @Override
    @Transactional
    public void disableShare(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        trip.setShareToken(null);
        tripRepository.save(trip);
        log.info("Disabled share for trip id={}", tripId);
    }

    @Override
    @Transactional(readOnly = true)
    public TripDetailResponse getSharedTrip(String shareToken) {
        Trip trip = tripRepository.findByShareToken(shareToken)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi được chia sẻ"));
        return buildTripDetailResponse(trip);
    }

    @Override
    @Transactional(readOnly = true)
    public TripDetailResponse getPublicTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .filter(Trip::isPublic)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        return buildTripDetailResponse(trip);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private Trip findTripByIdAndUserId(Long tripId, Long userId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        if (!trip.getUser().getId().equals(userId)) {
            throw AppException.forbidden("Bạn không có quyền với chuyến đi này");
        }
        return trip;
    }

    private TripDetailResponse buildTripDetailResponse(Trip trip) {
        List<TripDay> days = tripDayRepository.findByTripIdOrderByDayNumber(trip.getId());
        List<ChecklistItem> checklist = checklistItemRepository.findByTripIdOrderByDisplayOrder(trip.getId());

        List<Long> placeCacheIds = days.stream()
                .flatMap(day -> activityRepository.findByDayIdOrderByDisplayOrder(day.getId()).stream())
                .map(Activity::getPlaceCacheId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, PlaceCache> cacheMap = new HashMap<>();
        if (!placeCacheIds.isEmpty()) {
            placeCacheRepository.findAllById(placeCacheIds).forEach(p -> cacheMap.put(p.getId(), p));
        }

        // Còn enrich nền? Place chưa FULL (serpEnriched=false) VÀ vừa được đụng tới gần đây
        // (lastSyncedAt trong cửa sổ grace). Dùng MỐC THỜI GIAN thay vì serpSyncedAt==null để:
        //  (1) cờ giữ true xuyên suốt pass1 + retry-pass nền → FE còn poll, thấy ảnh/review 429 hồi phục;
        //  (2) cờ KHÔNG kẹt true vĩnh viễn nếu 1 row BASIC không bao giờ được nâng (vd dedup nâng row khác,
        //      hoặc enrich fail để serpSyncedAt=null) — quá grace thì thôi tính; trip cũ xem lại cũng không bật cờ.
        LocalDateTime enrichGraceThreshold = LocalDateTime.now().minus(ENRICHING_GRACE);
        boolean enriching = cacheMap.values().stream()
                .anyMatch(p -> !p.isSerpEnriched()
                        && p.getLastSyncedAt() != null
                        && p.getLastSyncedAt().isAfter(enrichGraceThreshold));

        List<TripDetailResponse.DayDetail> dayDetails = days.stream().map(day -> {
            List<Activity> activities = activityRepository.findByDayIdOrderByDisplayOrder(day.getId());
            List<TripDetailResponse.ActivityDetail> activityDetails = activities.stream()
                    .map(act -> toTripDetailActivityDetail(act, cacheMap)).toList();
            return TripDetailResponse.DayDetail.builder()
                    .id(day.getId())
                    .dayNumber(day.getDayNumber())
                    .date(day.getDate())
                    .dayCostVnd(activityDetails.stream().mapToLong(a -> a.getCostVnd() != null ? a.getCostVnd() : 0L).sum())
                    .activities(activityDetails)
                    .build();
        }).toList();

        List<TripDetailResponse.ChecklistItemDetail> checklistDetails = checklist.stream()
                .map(c -> TripDetailResponse.ChecklistItemDetail.builder()
                        .id(c.getId())
                        .category(c.getCategory() != null ? c.getCategory().name() : null)
                        .name(c.getName())
                        .isChecked(c.getIsChecked())
                        .displayOrder(c.getDisplayOrder())
                        .build()).toList();

        List<TripMemberResponse> members = tripMemberRepository.findByTripIdWithUser(trip.getId())
                .stream().map(m -> TripMemberResponse.builder()
                        .id(m.getId())
                        .userId(m.getUser() != null ? m.getUser().getId() : null)
                        .displayName(m.getDisplayName())
                        .avatarUrl(m.getUser() != null ? m.getUser().getAvatarUrl() : null)
                        .role(m.getRole())
                        .createdAt(m.getCreatedAt())
                        .build()).toList();

        return TripDetailResponse.builder()
                .id(trip.getId())
                .title(trip.getTitle())
                .departure(trip.getDeparture())
                .destination(trip.getDestination())
                .dateStart(trip.getDateStart())
                .dateEnd(trip.getDateEnd())
                .peopleCount(trip.getPeopleCount())
                .budgetVnd(trip.getBudgetVnd())
                .styles(trip.getStyles())
                .createdAt(trip.getCreatedAt())
                .updatedAt(trip.getUpdatedAt())
                .totalCostVnd(dayDetails.stream().mapToLong(d -> d.getDayCostVnd() != null ? d.getDayCostVnd() : 0L).sum())
                .shareToken(trip.getShareToken())
                .isPublic(trip.isPublic())
                .publishedAt(trip.getPublishedAt())
                .likesCount(trip.getLikesCount())
                .commentsCount(trip.getCommentsCount())
                .status(TripLifecycleStatus.of(trip.getDateStart(), trip.getDateEnd(), LocalDate.now()).name())
                .enriching(enriching)
                .createdAsPremium(trip.isGeneratedAsPremium())
                .user(TripDetailResponse.UserInfo.builder()
                        .id(trip.getUser().getId())
                        .email(trip.getUser().getEmail())
                        .fullName(trip.getUser().getFullName())
                        .avatarUrl(trip.getUser().getAvatarUrl())
                        .build())
                .members(members)
                .days(dayDetails)
                .checklist(checklistDetails)
                .build();
    }

    private TripSummaryResponse toSummaryResponse(Trip trip, String imageUrl) {
        return TripSummaryResponse.builder()
                .id(trip.getId())
                .userId(trip.getUser().getId())
                .userFullName(trip.getUser().getFullName())
                .userEmail(trip.getUser().getEmail())
                .title(trip.getTitle())
                .departure(trip.getDeparture())
                .destination(trip.getDestination())
                .dateStart(trip.getDateStart())
                .dateEnd(trip.getDateEnd())
                .peopleCount(trip.getPeopleCount())
                .budgetVnd(trip.getBudgetVnd())
                .styles(trip.getStyles())
                .createdAt(trip.getCreatedAt())
                .updatedAt(trip.getUpdatedAt())
                .imageUrl(imageUrl)
                .isPublic(trip.isPublic())
                .likesCount(trip.getLikesCount())
                .commentsCount(trip.getCommentsCount())
                .status(TripLifecycleStatus.of(trip.getDateStart(), trip.getDateEnd(), LocalDate.now()).name())
                .build();
    }

    /** FOOD, ATTRACTION, ACCOMMODATION, TRANSPORT đều cần geocode khi có searchQuery. OTHER bỏ qua. */
    private boolean shouldGeocode(ActivityType type) {
        return type == ActivityType.FOOD
                || type == ActivityType.ATTRACTION
                || type == ActivityType.ACCOMMODATION
                || type == ActivityType.TRANSPORT;
    }

    private ActivityType parseActivityType(String type) {
        if (type == null) return ActivityType.OTHER;
        try {
            return ActivityType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ActivityType.OTHER;
        }
    }

    private TripDetailResponse.ActivityDetail toTripDetailActivityDetail(Activity activity, Map<Long, PlaceCache> cacheMap) {
        String imgUrl = activity.getImageUrl();
        if ((imgUrl == null || imgUrl.isBlank()) && activity.getPlaceCacheId() != null) {
            PlaceCache p = cacheMap.get(activity.getPlaceCacheId());
            if (p != null) {
                imgUrl = extractFirstPhotoUrl(p.getPhotosJson());
            }
        }

        return TripDetailResponse.ActivityDetail.builder()
                .id(activity.getId())
                .startTime(activity.getStartTime())
                .name(activity.getName())
                .description(activity.getDescription())
                .type(activity.getType())
                .costVnd(activity.getCostVnd())
                .latitude(activity.getLatitude())
                .longitude(activity.getLongitude())
                .imageUrl(imgUrl)
                .bookingUrl(activity.getBookingUrl())
                .displayOrder(activity.getDisplayOrder())
                .placeCacheId(activity.getPlaceCacheId())
                .address(activity.getFormattedAddress())
                .build();
    }

    private String extractFirstPhotoUrl(String photosJson) {
        if (photosJson == null || photosJson.isBlank()) return null;
        try {
            List<Map<String, String>> raw = objectMapper.readValue(photosJson, new TypeReference<List<Map<String, String>>>() {});
            if (raw != null && !raw.isEmpty()) {
                Map<String, String> first = raw.get(0);
                String url = first.get("thumbnail");
                if (url == null || url.isBlank()) {
                    url = first.get("url");
                }
                return url;
            }
        } catch (Exception ignored) {}
        return null;
    }

}
