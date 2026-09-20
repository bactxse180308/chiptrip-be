package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.common.enums.TripLifecycleStatus;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.common.resilience.ProviderResilience;
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
import com.tranbac.chiptripbe.module.trip.dto.response.TripReadOnlyResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripEnrichmentResultDto;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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

    /** Backoff bền vững lần đầu; retry tiếp theo tăng theo cấp số nhân trong sweeper. */
    private static final int ENRICH_RETRY_BASE_MINUTES = 5;

    /** Trần backoff để trip lỗi tạm thời vẫn được thử lại nhưng không gây tải API liên tục. */
    private static final int ENRICH_RETRY_MAX_MINUTES = 60;

    /** Bỏ qua trip mới tạo dưới ngưỡng này khi sweep — job nền in-memory có thể còn đang chạy. */
    private static final int ENRICH_SWEEP_GRACE_MINUTES = 3;

    /** Lease DB đủ dài cho tối đa khoảng 20 địa điểm; ngăn background và sweeper chạy trùng. */
    private static final int ENRICH_CLAIM_LEASE_MINUTES = 30;

    /** Số trip xử lý mỗi lượt sweep — chặn 1 tick ôm quá nhiều việc. */
    private static final int ENRICH_SWEEP_BATCH = 20;

    private static final String ENRICHMENT_DESTINATION = "/queue/trip-enrichment";

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
    private final ProviderResilience providerResilience;
    private final TripGenerationPersistenceService tripGenerationPersistenceService;
    private final SimpMessagingTemplate messagingTemplate;
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

        // enrichmentPending persist = còn việc enrich nền → FE poll tiếp; nếu job nền dưới đây không
        // chạy được (queue DiscardPolicy / app restart), cờ giữ true → TripEnrichmentScheduler quét lại.
        boolean needsBackgroundEnrichment = outcome.backgroundJob() != null;
        TripGenerateResponse response = tripGenerationPersistenceService.persistGeneratedTrip(
                userId, request, aiCallResult, outcome.resolved(), needsBackgroundEnrichment);

        // Sau khi trip đã COMMIT: nâng các địa điểm ngày 2..N lên FULL ở luồng nền (best-effort).
        // FE poll GET /trips/{id} sẽ tự thấy ảnh/review nảy ra dần (đọc PlaceCache live).
        if (needsBackgroundEnrichment) {
            Long tripId = response.getId();
            BackgroundEnrichmentJob job = outcome.backgroundJob();
            // Bọc job: chỉ tắt enrichmentPending khi pass hiện tại đã hoàn tất mọi địa điểm. Nếu chưa,
            // DiscardPolicy bỏ / app restart trước khi chạy → cờ giữ true → sweeper hoàn tất (durable).
            Runnable durableJob = () -> {
                if (!tryClaimEnrichment(tripId)) return;
                Duration activeCooldown = enrichmentCooldownRemaining();
                if (!activeCooldown.isZero()) {
                    tripRepository.deferEnrichmentRetry(
                            tripId, LocalDateTime.now().plus(activeCooldown));
                    return;
                }
                if (job.run(tripId)) {
                    completeEnrichment(userId, tripId);
                } else {
                    int previousAttempts = tripRepository.findEnrichmentAttemptsById(tripId).orElse(0);
                    tripRepository.scheduleEnrichmentRetry(
                            tripId, nextEnrichmentRetryAt(LocalDateTime.now(), previousAttempts + 1));
                }
            };
            try {
                backgroundEnrichmentExecutor.execute(durableJob);
            } catch (Exception e) {
                // Không submit được → giữ cờ pending để sweeper quét lại (không tắt cờ ở đây).
                log.warn("Background enrich không thể submit cho trip id={}: {} (sweeper sẽ quét lại)",
                        response.getId(), e.getMessage());
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
    private record ResolveOutcome(Map<AiItineraryResult.AiActivity, PlaceCache> resolved,
                                  BackgroundEnrichmentJob backgroundJob) {}

    @FunctionalInterface
    private interface BackgroundEnrichmentJob {
        /** @return true khi mọi địa điểm đã enrich đầy đủ và có thể tắt cờ pending. */
        boolean run(Long tripId);
    }

    /** Mô tả 1 nhóm địa điểm cần enrich FULL ở luồng nền. */
    private record BgEnrich(String searchQuery, String destination,
                            List<PlaceEnrichmentService.GeoAnchor> anchors, boolean preferSerp, int minDay,
                            boolean accommodation, LocalDate checkIn, LocalDate checkOut, Integer adults) {}

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
        // BgEnrich cho TỪNG nhóm (song song với groupList/futures) + cờ nhóm nào BASIC —
        // sau join sẽ chọn nhóm nào cần enrich nền (BASIC ngày 2..N, hoặc FULL mà SerpApi miss).
        List<BgEnrich> groupBg = new ArrayList<>(groupList.size());
        List<Boolean> groupIsBasic = new ArrayList<>(groupList.size());
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
            LocalDate accommodationCheckIn = hasAccommodation
                    ? group.stream().map(GeoActivity::date).filter(Objects::nonNull)
                            .min(LocalDate::compareTo).orElse(tripStart)
                    : null;
            LocalDate accommodationCheckOut = hasAccommodation
                    ? group.stream().map(GeoActivity::date).filter(Objects::nonNull)
                            .max(LocalDate::compareTo).map(d -> d.plusDays(1)).orElse(tripEnd)
                    : null;
            groupBg.add(new BgEnrich(first.getSearchQuery(), destination, List.copyOf(anchors),
                    preferSerp, minDay, hasAccommodation,
                    accommodationCheckIn, accommodationCheckOut, adults));
            groupIsBasic.add(depth == PlaceEnrichmentService.EnrichmentDepth.BASIC);

            final boolean fHasAccommodation = hasAccommodation;
            final boolean fPreferSerp = preferSerp;
            final List<PlaceEnrichmentService.GeoAnchor> fAnchors = anchors;
            final PlaceEnrichmentService.EnrichmentDepth fDepth = depth;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    // allowScraper=false: foreground KHÔNG chờ scraper (chậm ~10-15s/điểm) — nhóm chưa
                    // enrich đủ sẽ được scraper enrich ở luồng nền, để generate trả nhanh dưới deadline.
                    Optional<PlaceCache> placeOpt = placeEnrichmentService.resolvePlace(
                            first.getSearchQuery(), destination, fAnchors, deadline, fPreferSerp, fDepth,
                            false, false);
                    placeOpt.ifPresent(cache -> {
                        if (fHasAccommodation) {
                            placeEnrichmentService.enrichAccommodation(
                                    cache, accommodationCheckIn, accommodationCheckOut, adults, deadline);
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
        // ghi xen vào map trong lúc persist đang đọc; mỗi group nguyên tử (có place cả nhóm).
        // Đồng thời gom danh sách enrich nền: (a) nhóm BASIC ngày 2..N (luôn nâng FULL như cũ);
        // (b) nhóm FULL (ngày 1 / khách sạn) mà SerpApi miss → serpEnriched=false: scraper enrich
        // ở luồng nền (foreground đã bỏ scraper). Nhóm geocode fail (cache==null) cũng phải retry:
        // provider có thể chỉ đang cooldown, và kết quả nền sẽ được gắn lại vào Activity sau khi persist.
        List<BgEnrich> bgList = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<PlaceCache> f = futures.get(i);
            boolean done = f.isDone() && !f.isCompletedExceptionally();
            PlaceCache cache = done ? f.getNow(null) : null;
            if (cache != null) {
                for (GeoActivity ga : groupList.get(i)) {
                    result.put(ga.act(), cache);
                }
            }
            if (cache == null || groupIsBasic.get(i) || !cache.isSerpEnriched()) {
                bgList.add(groupBg.get(i));
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
    private BackgroundEnrichmentJob buildBackgroundEnrichJob(List<BgEnrich> bgList) {
        if (bgList.isEmpty()) return null;
        bgList.sort(Comparator.comparingInt(BgEnrich::minDay));
        List<BgEnrich> jobs = List.copyOf(bgList);
        return tripId -> {
            log.info("Background FULL enrich start tripId={}: {} place(s)", tripId, jobs.size());
            Map<String, List<Activity>> activitiesByCanonicalKey = new HashMap<>();
            for (Activity activity : activityRepository
                    .findByDayTripIdOrderByDayDayNumberAscDisplayOrderAsc(tripId)) {
                ActivityType type = activity.getType() != null ? activity.getType() : ActivityType.OTHER;
                if (!shouldGeocode(type)) continue;
                if (activity.getSearchQuery() == null || activity.getSearchQuery().isBlank()) continue;
                String key = placeEnrichmentService.canonicalKey(activity.getSearchQuery());
                activitiesByCanonicalKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(activity);
            }
            int ok = 0;
            for (BgEnrich b : jobs) {
                Optional<PlaceCache> resolved = resolveEnrichment(b, false);
                if (resolved.isEmpty()) continue;
                PlaceCache cache = resolved.get();
                String key = placeEnrichmentService.canonicalKey(b.searchQuery());
                boolean locationsUpdated = updateActivityLocations(
                        activitiesByCanonicalKey.getOrDefault(key, List.of()), cache);
                if (cache.isSerpEnriched() && locationsUpdated) ok++;
            }
            int incomplete = jobs.size() - ok;
            log.info("Background FULL enrich pass1 tripId={}: {}/{} đủ, {} hẹn retry bền vững",
                    tripId, ok, jobs.size(), incomplete);
            return incomplete == 0;
        };
    }

    /** 1 lần enrich FULL nền cho 1 nhóm; trả row kết quả để caller kiểm tra và gắn lại Activity. */
    private Optional<PlaceCache> resolveEnrichment(BgEnrich b, boolean forceRefresh) {
        try {
            Optional<PlaceCache> resolved = placeEnrichmentService.resolvePlace(
                    b.searchQuery(), b.destination(), b.anchors(), null, b.preferSerp(),
                    PlaceEnrichmentService.EnrichmentDepth.FULL, forceRefresh);
            if (b.accommodation()) {
                // Giá/link phụ thuộc nhà cung cấp và có thể không tồn tại nên vẫn là best-effort;
                // cờ pending đo FULL place data (tọa độ/ảnh/review), không đo availability khách sạn.
                resolved.ifPresent(cache -> placeEnrichmentService.enrichAccommodation(
                        cache, b.checkIn(), b.checkOut(), b.adults(), null));
            }
            return resolved;
        } catch (Exception e) {
            log.warn("Background FULL enrich {} failed for '{}': {}",
                    forceRefresh ? "retry" : "pass1", b.searchQuery(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Durable safety-net cho enrich nền (P1): job nền ngày 2..N submit vào executor in-memory là
     * best-effort — mất khi app restart / queue DiscardPolicy / provider down lâu. Method này (gọi
     * bởi TripEnrichmentScheduler) quét các trip còn enrichmentPending mà job nền lẽ ra đã xong,
     * reconstruct danh sách địa điểm chưa FULL TỪ DB rồi enrich lại; chỉ tắt cờ khi thực sự xong.
     *
     * NOT_SUPPORTED để KHÔNG giữ DB transaction trong lúc gọi Goong/SerpApi (giống generateTrip).
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sweepPendingEnrichments() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createdBefore = now.minusMinutes(ENRICH_SWEEP_GRACE_MINUTES);
        List<Trip> pending = tripRepository.findPendingEnrichment(
                createdBefore, now, PageRequest.of(0, ENRICH_SWEEP_BATCH));
        if (pending.isEmpty()) return;
        log.info("TripEnrichmentSweeper: {} trip còn enrichment pending → enrich lại", pending.size());
        for (Trip trip : pending) {
            if (!tryClaimEnrichment(trip.getId())) continue;
            Duration activeCooldown = enrichmentCooldownRemaining();
            if (!activeCooldown.isZero()) {
                LocalDateTime retryAt = LocalDateTime.now().plus(activeCooldown);
                tripRepository.deferEnrichmentRetry(trip.getId(), retryAt);
                log.info("Sweep enrich trip id={} hoãn đến {} vì provider còn cooldown {}s",
                        trip.getId(), retryAt, activeCooldown.toSeconds());
                continue;
            }

            int remaining;
            try {
                remaining = reEnrichTripPlaces(trip);
            } catch (Exception e) {
                log.warn("Sweep enrich trip id={} lỗi: {}", trip.getId(), e.getMessage());
                remaining = Integer.MAX_VALUE;
            }
            if (remaining == 0) {
                completeEnrichment(trip.getUser().getId(), trip.getId());
            } else {
                int previousAttempts = trip.getEnrichmentAttempts() != null
                        ? trip.getEnrichmentAttempts()
                        : 0;
                int failedAttempts = previousAttempts + 1;
                LocalDateTime retryAt = nextEnrichmentRetryAt(LocalDateTime.now(), failedAttempts);
                tripRepository.scheduleEnrichmentRetry(trip.getId(), retryAt);
                log.info("Sweep enrich trip id={} còn {} nhóm, attempt={} → thử lại lúc {}",
                        trip.getId(), remaining, failedAttempts, retryAt);
            }
        }
    }

    private boolean tryClaimEnrichment(Long tripId) {
        LocalDateTime now = LocalDateTime.now();
        int claimed = tripRepository.tryClaimEnrichment(
                tripId, now, now.plusMinutes(ENRICH_CLAIM_LEASE_MINUTES));
        if (claimed == 0) {
            log.debug("Skip enrichment trip id={}: đã được worker khác claim hoặc không còn pending", tripId);
        }
        return claimed > 0;
    }

    private Duration enrichmentCooldownRemaining() {
        return providerResilience.cooldownRemaining(
                ProviderResilience.SERPAPI, ProviderResilience.GOONG_GEOCODE);
    }

    private LocalDateTime nextEnrichmentRetryAt(LocalDateTime now, int failedAttempts) {
        int exponent = Math.min(4, Math.max(0, failedAttempts - 1));
        long backoffMinutes = Math.min(
                ENRICH_RETRY_MAX_MINUTES,
                ENRICH_RETRY_BASE_MINUTES * (1L << exponent));
        Duration backoff = Duration.ofMinutes(backoffMinutes);
        Duration cooldown = enrichmentCooldownRemaining();
        Duration delay = cooldown.compareTo(backoff) > 0 ? cooldown : backoff;
        return now.plus(delay);
    }

    /**
     * Reconstruct từ DB các nhóm địa điểm CHƯA FULL (BASIC/serp-miss) của 1 trip rồi enrich lại FULL.
     * Trả số nhóm vẫn chưa FULL sau lượt enrich (0 = đã đủ hết). Activity chưa có placeCacheId cũng
     * được resolve lại và gắn cache mới vào DB; đây là trường hợp Goong tạm cooldown ở lượt đầu.
     */
    private int reEnrichTripPlaces(Trip trip) {
        List<TripDay> days = tripDayRepository.findByTripIdOrderByDayNumber(trip.getId());
        List<Activity> activities = new ArrayList<>();
        Map<Long, LocalDate> activityDates = new HashMap<>();
        for (TripDay day : days) {
            List<Activity> dayActivities = activityRepository.findByDayIdOrderByDisplayOrder(day.getId());
            activities.addAll(dayActivities);
            for (Activity activity : dayActivities) {
                if (activity.getId() != null) activityDates.put(activity.getId(), day.getDate());
            }
        }
        List<Long> placeCacheIds = activities.stream()
                .map(Activity::getPlaceCacheId).filter(Objects::nonNull).distinct().toList();
        Map<Long, PlaceCache> cacheMap = new HashMap<>();
        if (!placeCacheIds.isEmpty()) {
            placeCacheRepository.findAllById(placeCacheIds).forEach(p -> cacheMap.put(p.getId(), p));
        }

        // Dedup theo canonicalKey (như luồng foreground); preferSerp theo type (FOOD/ATTRACTION ưu tiên
        // SerpApi). Anchors để rỗng: row đã có trong cache theo canonicalKey → enrich lại đúng row đó.
        Map<String, List<Activity>> activitiesByGroup = new LinkedHashMap<>();
        for (Activity act : activities) {
            ActivityType type = act.getType() != null ? act.getType() : ActivityType.OTHER;
            if (!shouldGeocode(type)) continue;
            String q = act.getSearchQuery();
            if (q == null || q.isBlank()) continue;
            PlaceCache pc = act.getPlaceCacheId() != null ? cacheMap.get(act.getPlaceCacheId()) : null;
            if (pc != null && pc.isSerpEnriched()) continue;
            String canonicalKey = placeEnrichmentService.canonicalKey(q);
            activitiesByGroup.computeIfAbsent(canonicalKey, ignored -> new ArrayList<>()).add(act);
        }
        if (activitiesByGroup.isEmpty()) return 0;
        int stillIncomplete = 0;
        // forceRefresh sau lần thử đầu để bỏ qua backoff nếu row vẫn kẹt serpEnriched=false.
        boolean forceRefresh = trip.getEnrichmentAttempts() != null && trip.getEnrichmentAttempts() > 0;
        for (Map.Entry<String, List<Activity>> entry : activitiesByGroup.entrySet()) {
            List<Activity> groupActivities = entry.getValue();
            Activity first = groupActivities.get(0);
            boolean accommodation = groupActivities.stream()
                    .anyMatch(a -> a.getType() == ActivityType.ACCOMMODATION);
            boolean hasTransport = groupActivities.stream()
                    .anyMatch(a -> a.getType() == ActivityType.TRANSPORT);
            boolean hasNamedPoi = groupActivities.stream()
                    .anyMatch(a -> a.getType() == ActivityType.FOOD || a.getType() == ActivityType.ATTRACTION);
            boolean preferSerp = hasNamedPoi && !hasTransport && !accommodation;
            LocalDate checkIn = accommodation
                    ? groupActivities.stream().map(Activity::getId).map(activityDates::get)
                            .filter(Objects::nonNull).min(LocalDate::compareTo).orElse(trip.getDateStart())
                    : null;
            LocalDate checkOut = accommodation
                    ? groupActivities.stream().map(Activity::getId).map(activityDates::get)
                            .filter(Objects::nonNull).max(LocalDate::compareTo).map(d -> d.plusDays(1))
                            .orElse(trip.getDateEnd())
                    : null;
            BgEnrich background = new BgEnrich(first.getSearchQuery(), trip.getDestination(), List.of(),
                    preferSerp, 0, accommodation, checkIn, checkOut, trip.getPeopleCount());
            Optional<PlaceCache> resolved = resolveEnrichment(background, forceRefresh);
            if (resolved.isEmpty()) {
                stillIncomplete++;
                continue;
            }
            PlaceCache cache = resolved.get();
            boolean locationsUpdated = updateActivityLocations(groupActivities, cache);
            if (!cache.isSerpEnriched() || !locationsUpdated) stillIncomplete++;
        }
        return stillIncomplete;
    }

    private boolean updateActivityLocations(List<Activity> activities, PlaceCache cache) {
        if (cache.getId() == null || activities.isEmpty()) return false;
        String provider = cache.getGoongPlaceId() != null ? "goong" : "serpapi";
        boolean allUpdated = true;
        for (Activity activity : activities) {
            if (activity.getId() == null) {
                allUpdated = false;
                continue;
            }
            int updated = activityRepository.updateResolvedPlaceIfQueryUnchanged(
                    activity.getId(),
                    activity.getSearchQuery(),
                    cache.getId(),
                    cache.getLatitude(),
                    cache.getLongitude(),
                    cache.getGoongPlaceId(),
                    cache.getAddress(),
                    provider);
            if (updated == 0) allUpdated = false;
        }
        return allUpdated;
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
        LocalDate copyStart = isOwner ? original.getDateStart() : LocalDate.now(VN_ZONE);
        LocalDate copyEnd = copyStart.plusDays(ChronoUnit.DAYS.between(original.getDateStart(), original.getDateEnd()));
        Trip clone = Trip.builder()
                .user(cloner)
                .title(original.getTitle() + " (Copy)")
                .departure(original.getDeparture())
                .destination(original.getDestination())
                .dateStart(copyStart)
                .dateEnd(copyEnd)
                .peopleCount(original.getPeopleCount())
                .budgetVnd(isOwner ? original.getBudgetVnd() : 0L)
                .styles(original.getStyles())
                .build();
        clone = tripRepository.save(clone);
        tripMemberService.seedOwner(clone, cloner);

        // Clone days and activities
        for (TripDay originalDay : original.getDays()) {
            TripDay cloneDay = TripDay.builder()
                    .trip(clone)
                    .dayNumber(originalDay.getDayNumber())
                    .date(isOwner ? originalDay.getDate() : copyStart.plusDays(originalDay.getDayNumber() - 1L))
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
                        .bookingUrl(isOwner ? originalActivity.getBookingUrl() : null)
                        .displayOrder(originalActivity.getDisplayOrder())
                        .build();
                activityRepository.save(cloneActivity);
            }
        }

        // Clone checklist
        for (ChecklistItem originalItem : isOwner ? original.getChecklist() : List.<ChecklistItem>of()) {
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
        String token = TripShareTokens.create();
        trip.setShareToken(token);
        tripRepository.save(trip);
        log.info("Enabled share for trip id={}", tripId);
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
    public TripReadOnlyResponse getSharedTrip(String shareToken) {
        if (!TripShareTokens.isCurrent(shareToken)) {
            throw AppException.notFound("Link chia sẻ đã hết hiệu lực. Chủ chuyến đi cần tạo link mới.");
        }
        Trip trip = tripRepository.findByShareToken(shareToken)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi được chia sẻ"));
        return buildReadOnlyResponse(trip, true);
    }

    @Override
    @Transactional(readOnly = true)
    public TripReadOnlyResponse getPublicTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .filter(Trip::isPublic)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        return buildReadOnlyResponse(trip, false);
    }

    private TripReadOnlyResponse buildReadOnlyResponse(Trip trip, boolean shared) {
        List<TripDay> days = tripDayRepository.findByTripIdOrderByDayNumber(trip.getId());
        List<Activity> activities = activityRepository.findByTripIdWithDayOrderByDayAndOrder(trip.getId());
        Map<Long, List<Activity>> byDay = activities.stream()
                .collect(java.util.stream.Collectors.groupingBy(a -> a.getDay().getId()));
        List<Long> placeIds = activities.stream().map(Activity::getPlaceCacheId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, PlaceCache> places = new HashMap<>();
        if (!placeIds.isEmpty()) placeCacheRepository.findAllById(placeIds).forEach(p -> places.put(p.getId(), p));
        List<TripReadOnlyResponse.Day> resultDays = days.stream().map(day -> {
            List<TripReadOnlyResponse.Activity> resultActivities = byDay.getOrDefault(day.getId(), List.of()).stream()
                    .map(a -> toTripDetailActivityDetail(a, places))
                    .map(a -> new TripReadOnlyResponse.Activity(a.getId(), a.getStartTime(), a.getName(), a.getDescription(),
                            a.getType(), a.getCostVnd(), a.getLatitude(), a.getLongitude(), a.getImageUrl(),
                            shared ? a.getBookingUrl() : null, a.getDisplayOrder(), a.getPlaceCacheId(), a.getAddress()))
                    .toList();
            long cost = resultActivities.stream().mapToLong(a -> a.costVnd() == null ? 0 : a.costVnd()).sum();
            return new TripReadOnlyResponse.Day(day.getId(), day.getDayNumber(), shared ? day.getDate() : null, cost, resultActivities);
        }).toList();
        User owner = trip.getUser();
        return new TripReadOnlyResponse(trip.getId(), trip.getTitle(), trip.getDeparture(), trip.getDestination(),
                shared ? trip.getDateStart() : null, shared ? trip.getDateEnd() : null,
                trip.getPeopleCount(), trip.getStyles(), resultDays.stream().mapToLong(TripReadOnlyResponse.Day::dayCostVnd).sum(),
                trip.isPublic(), trip.getLikesCount(), trip.getCommentsCount(),
                shared ? TripLifecycleStatus.of(trip.getDateStart(), trip.getDateEnd(), LocalDate.now(VN_ZONE)).name() : null,
                trip.isEnrichmentPending(), new TripReadOnlyResponse.UserInfo(owner.getId(), owner.getFullName(), owner.getAvatarUrl()), resultDays);
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
        List<Activity> activities = activityRepository.findByTripIdWithDayOrderByDayAndOrder(trip.getId());
        Map<Long, List<Activity>> activitiesByDay = activities.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        activity -> activity.getDay().getId(), LinkedHashMap::new, java.util.stream.Collectors.toList()));

        List<Long> placeCacheIds = activities.stream()
                .map(Activity::getPlaceCacheId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, PlaceCache> cacheMap = new HashMap<>();
        if (!placeCacheIds.isEmpty()) {
            placeCacheRepository.findAllById(placeCacheIds).forEach(p -> cacheMap.put(p.getId(), p));
        }

        // Còn enrich nền? Đọc TRỰC TIẾP cờ persist trip.enrichmentPending (bật lúc tạo trip nếu có
        // việc enrich nền; job nền / TripEnrichmentScheduler tắt khi xong) — durable, thay heuristic
        // lastSyncedAt+grace cũ vốn có thể tắt sớm khi job nền chết, khiến FE ngừng poll dù data chưa FULL.
        boolean enriching = trip.isEnrichmentPending();

        List<TripDetailResponse.DayDetail> dayDetails = days.stream().map(day -> {
            List<TripDetailResponse.ActivityDetail> activityDetails = activitiesByDay
                    .getOrDefault(day.getId(), List.of()).stream()
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
                .shareToken(TripShareTokens.isCurrent(trip.getShareToken()) ? trip.getShareToken() : null)
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

    /** Tắt cờ durable trước, sau đó push best-effort để FE refetch ngay thay vì chờ polling. */
    private void completeEnrichment(Long userId, Long tripId) {
        tripRepository.markEnrichmentDone(tripId);
        log.info("Trip enrichment complete tripId={} userId={}", tripId, userId);
        try {
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(userId), ENRICHMENT_DESTINATION, TripEnrichmentResultDto.done(tripId));
        } catch (MessagingException ex) {
            log.warn("Failed to push trip-enrichment result via WS to userId={}: {}", userId, ex.getMessage());
        }
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
