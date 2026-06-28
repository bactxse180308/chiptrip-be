package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.config.AiProperties;
import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.dto.AiActivityAlternativesResult;
import com.tranbac.chiptripbe.module.ai.service.AiKeyPool;
import com.tranbac.chiptripbe.module.ai.service.AiUsageService;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceEnrichmentService;
import com.tranbac.chiptripbe.module.trip.dto.request.ActivityAlternativesRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.ReplaceActivityRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.ActivityAlternativeOptionResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.ActivityAlternativesResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.ReplaceActivityResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.entity.Activity;
import com.tranbac.chiptripbe.module.trip.entity.ActivityAlternativeSession;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import com.tranbac.chiptripbe.module.trip.enums.ActivityAlternativeCategory;
import com.tranbac.chiptripbe.module.trip.enums.ActivityAlternativeSessionStatus;
import com.tranbac.chiptripbe.module.trip.repository.ActivityAlternativeSessionRepository;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.ActivityAlternativeService;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import com.tranbac.chiptripbe.module.user.service.EntitlementService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ActivityAlternativeServiceImpl implements ActivityAlternativeService {

    private static final int DEFAULT_LIMIT = 4;
    private static final int PAID_SWAP_UNITS = 25;
    private static final int CREDIT_UNIT_SCALE = 100;
    private static final int SESSION_TTL_MINUTES = 15;
    private static final int MAX_ACTIVITY_SEARCH_QUERY_LENGTH = 300;
    private static final Set<String> GENERIC_PLACE_TOKENS = Set.of(
            "nha", "hang", "quan", "an", "uong", "mon", "food", "restaurant",
            "cafe", "coffee", "khach", "san", "hotel", "resort", "homestay",
            "tham", "du", "lich", "tourist", "attraction", "diem",
            "den", "transport", "airport", "station"
    );

    private final TripRepository tripRepository;
    private final TripDayRepository tripDayRepository;
    private final ActivityRepository activityRepository;
    private final ActivityAlternativeSessionRepository sessionRepository;
    private final PlaceCacheRepository placeCacheRepository;
    private final UserRepository userRepository;
    private final EntitlementService entitlementService;
    private final PlaceEnrichmentService placeEnrichmentService;
    @Qualifier("enrichmentExecutor")
    private final Executor enrichmentExecutor;
    private final WebClient.Builder webClientBuilder;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final AiUsageService aiUsageService;
    private final AiKeyPool aiKeyPool;

    private WebClient aiApiClient;

    @PostConstruct
    void init() {
        // Không gắn Authorization cố định: key set theo từng request để xoay vòng khi một key hết hạn mức.
        aiApiClient = webClientBuilder
                .baseUrl(aiProperties.getOpenaiCompat().getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public ActivityAlternativesResponse getActiveAlternatives(Long userId, Long tripId, Long dayId, Long activityId) {
        AlternativeContext context = loadContext(userId, tripId, dayId, activityId);
        boolean premium = entitlementService.isPremium(userId);
        return findActivePendingSession(userId, tripId, dayId, activityId)
                .map(session -> toAlternativesResponse(session, context.trip(), premium))
                .orElseGet(() -> emptyAlternativesResponse(context.trip(), premium));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ActivityAlternativesResponse createAlternatives(Long userId, Long tripId, Long dayId, Long activityId,
                                                           ActivityAlternativesRequest request) {
        AlternativeContext context = loadContext(userId, tripId, dayId, activityId);
        // Gate "createdAsPremium HOẶC premium hiện tại": chuyến Premium vẫn đổi được khi paid về 0
        // (còn lượt free); nạp gói rồi thì đổi được cả chuyến tạo lúc còn Normal. Normal+chưa nạp → 403.
        boolean premium = entitlementService.isPremium(userId);
        if (!context.trip().isGeneratedAsPremium() && !premium) {
            throw AppException.premiumRequired();
        }
        int limit = normalizeLimit(request.getLimit());
        ActivityAlternativeCategory category = request.getCategory();
        validateCategory(context, category);

        Optional<ActivityAlternativeSession> existingSession =
                findActivePendingSession(userId, tripId, dayId, activityId, category);
        if (existingSession.isPresent()) {
            return toAlternativesResponse(existingSession.get(), context.trip(), premium);
        }

        AiAlternativeCallResult aiResult = callAi(context, category);
        // Ghi audit chi phí LLM ngay sau khi gọi (kể cả khi sau đó không lọc được option nào)
        // để admin theo dõi token/chi phí. Lỗi ghi log KHÔNG được phá luồng gợi ý.
        try {
            aiUsageService.recordUsage(userId, tripId, aiResult.promptTokens(), aiResult.completionTokens());
        } catch (Exception e) {
            log.warn("Failed to record AiUsage for activity alternatives: {}", e.getMessage());
        }
        List<ActivityAlternativeOptionResponse> options = buildEnrichedOptions(context, aiResult.result(), category, limit);
        if (options.isEmpty()) {
            throw AppException.internal("AI chua tim duoc dia diem thay the phu hop. Vui long thu lai.");
        }

        ActivityAlternativeSession session = sessionRepository.save(ActivityAlternativeSession.builder()
                .userId(userId)
                .tripId(tripId)
                .dayId(dayId)
                .activityId(activityId)
                .category(category)
                .status(ActivityAlternativeSessionStatus.PENDING)
                .optionsJson(writeOptions(options))
                .promptTokens(aiResult.promptTokens())
                .completionTokens(aiResult.completionTokens())
                .expiresAt(LocalDateTime.now().plusMinutes(SESSION_TTL_MINUTES))
                .build());

        return toAlternativesResponse(session, context.trip(), options, premium);
    }

    @Override
    @Transactional
    public ReplaceActivityResponse replaceActivity(Long userId, Long tripId, Long dayId, Long activityId,
                                                   ReplaceActivityRequest request) {
        ActivityAlternativeSession session = sessionRepository
                .findByIdAndUserIdAndTripIdAndDayIdAndActivityIdForUpdate(
                        request.getSessionId(), userId, tripId, dayId, activityId)
                .orElseThrow(() -> AppException.notFound("Khong tim thay phien goi y thay the"));

        if (session.getStatus() != ActivityAlternativeSessionStatus.PENDING) {
            throw AppException.badRequest("Phien goi y nay da duoc su dung");
        }
        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus(ActivityAlternativeSessionStatus.EXPIRED);
            throw AppException.badRequest("Phien goi y da het han. Vui long tao goi y moi.");
        }

        Trip trip = tripRepository.findByIdAndUserIdForUpdate(tripId, userId)
                .orElseThrow(() -> AppException.notFound("Khong tim thay chuyen di"));
        tripDayRepository.findByIdAndTripId(dayId, tripId)
                .orElseThrow(() -> AppException.notFound("Khong tim thay ngay trong chuyen di"));

        Activity activity = activityRepository.findByIdAndDayTripUserId(activityId, userId)
                .orElseThrow(() -> AppException.notFound("Khong tim thay hoat dong"));
        if (!activity.getDay().getId().equals(dayId)) {
            throw AppException.badRequest("Hoat dong khong thuoc ngay nay");
        }

        List<ActivityAlternativeOptionResponse> options = readOptions(session.getOptionsJson());
        ActivityAlternativeOptionResponse selected = options.stream()
                .filter(option -> request.getOptionId().equals(option.getOptionId()))
                .findFirst()
                .orElseThrow(() -> AppException.badRequest("Lua chon thay the khong hop le"));

        boolean premium = entitlementService.isPremium(userId);
        ChargeResult charge = consumeQuotaOrCredit(trip, userId, premium);
        List<Activity> targets = replacementTargets(tripId, activity, selected);
        PlaceCache cache = selected.getPlaceCacheId() != null
                ? placeCacheRepository.findById(selected.getPlaceCacheId()).orElse(null)
                : null;
        for (Activity target : targets) {
            applyOption(target, selected, cache);
        }
        activityRepository.saveAll(targets);
        expireOtherPendingSessions(userId, tripId, session.getId(), targets);

        session.setStatus(ActivityAlternativeSessionStatus.APPLIED);
        sessionRepository.save(session);

        Integer remainingUnits = userRepository.findAiCreditUnitsById(userId);
        if (remainingUnits != null) {
            userRepository.syncWholeCredits(userId, Math.max(0, remainingUnits / CREDIT_UNIT_SCALE));
        }
        return ReplaceActivityResponse.builder()
                .activity(toActivityDetail(activity))
                .freeSwapsRemaining(freeSwapsRemaining(trip, premium))
                .chargedUnits(charge.chargedUnits())
                .chargedCredits(unitsToCredits(charge.chargedUnits()))
                .aiCreditUnitsRemaining(remainingUnits)
                .aiCreditBalance(unitsToCredits(remainingUnits != null ? remainingUnits : 0))
                .build();
    }

    private AlternativeContext loadContext(Long userId, Long tripId, Long dayId, Long activityId) {
        Trip trip = tripRepository.findByIdAndUserId(tripId, userId)
                .orElseThrow(() -> AppException.notFound("Khong tim thay chuyen di"));
        TripDay day = tripDayRepository.findByIdAndTripId(dayId, tripId)
                .orElseThrow(() -> AppException.notFound("Khong tim thay ngay trong chuyen di"));
        Activity current = activityRepository.findByIdAndDayTripUserId(activityId, userId)
                .orElseThrow(() -> AppException.notFound("Khong tim thay hoat dong"));
        if (!current.getDay().getId().equals(dayId)) {
            throw AppException.badRequest("Hoat dong khong thuoc ngay nay");
        }
        List<Activity> activities = activityRepository.findByTripIdWithDayOrderByDayAndOrder(tripId);
        return new AlternativeContext(trip, day, current, activities);
    }

    private Optional<ActivityAlternativeSession> findActivePendingSession(
            Long userId,
            Long tripId,
            Long dayId,
            Long activityId) {
        return sessionRepository.findFirstByUserIdAndTripIdAndDayIdAndActivityIdAndStatusAndExpiresAtAfterOrderByIdDesc(
                userId,
                tripId,
                dayId,
                activityId,
                ActivityAlternativeSessionStatus.PENDING,
                LocalDateTime.now());
    }

    private Optional<ActivityAlternativeSession> findActivePendingSession(
            Long userId,
            Long tripId,
            Long dayId,
            Long activityId,
            ActivityAlternativeCategory category) {
        return sessionRepository.findFirstByUserIdAndTripIdAndDayIdAndActivityIdAndCategoryAndStatusAndExpiresAtAfterOrderByIdDesc(
                userId,
                tripId,
                dayId,
                activityId,
                category,
                ActivityAlternativeSessionStatus.PENDING,
                LocalDateTime.now());
    }

    private ActivityAlternativesResponse emptyAlternativesResponse(Trip trip, boolean premium) {
        int chargeUnits = chargeUnitsIfApplied(trip, premium);
        return ActivityAlternativesResponse.builder()
                .freeSwapsRemaining(freeSwapsRemaining(trip, premium))
                .chargeUnitsIfApplied(chargeUnits)
                .chargeCreditsIfApplied(unitsToCredits(chargeUnits))
                .options(List.of())
                .build();
    }

    private ActivityAlternativesResponse toAlternativesResponse(ActivityAlternativeSession session, Trip trip, boolean premium) {
        return toAlternativesResponse(session, trip, readOptions(session.getOptionsJson()), premium);
    }

    private ActivityAlternativesResponse toAlternativesResponse(
            ActivityAlternativeSession session,
            Trip trip,
            List<ActivityAlternativeOptionResponse> options,
            boolean premium) {
        int chargeUnits = chargeUnitsIfApplied(trip, premium);
        return ActivityAlternativesResponse.builder()
                .sessionId(session.getId())
                .category(session.getCategory())
                .freeSwapsRemaining(freeSwapsRemaining(trip, premium))
                .chargeUnitsIfApplied(chargeUnits)
                .chargeCreditsIfApplied(unitsToCredits(chargeUnits))
                .options(options)
                .build();
    }

    private void validateCategory(AlternativeContext context, ActivityAlternativeCategory category) {
        if (category == null) {
            throw AppException.badRequest("Vui long chon loai hoat dong muon thay the");
        }
        if (category == ActivityAlternativeCategory.SAME_TYPE) {
            throw AppException.badRequest("Lua chon cung loai khong con duoc ho tro. Vui long chon loai cu the.");
        }

        Activity current = context.current();
        if (current.getType() == ActivityType.ACCOMMODATION) {
            if (category != ActivityAlternativeCategory.HOTEL) {
                throw AppException.badRequest("Hoat dong khach san chi duoc doi sang khach san khac.");
            }
            return;
        }

        boolean previousIsAccommodation = previousSameDayActivity(context)
                .map(activity -> activity.getType() == ActivityType.ACCOMMODATION)
                .orElse(false);
        if (category == ActivityAlternativeCategory.HOTEL && previousIsAccommodation) {
            throw AppException.badRequest("Khong the chen them khach san ngay sau hoat dong khach san.");
        }
    }

    private AiAlternativeCallResult callAi(AlternativeContext context, ActivityAlternativeCategory category) {
        Map<String, Object> requestBody = buildAiRequest(context, category);
        // Đủ lượt để xoay qua mọi key (khi quota) cộng thêm ngân sách retry cho lỗi tạm thời.
        int maxAttempts = aiKeyPool.size() + aiProperties.getMaxRetries();
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String apiKey = aiKeyPool.current();
            boolean lastAttempt = attempt == maxAttempts - 1;
            try {
                Map<String, Object> response = callLlm(requestBody, apiKey);
                String content = extractContent(response);
                AiActivityAlternativesResult result = parseJson(content);
                int[] tokens = extractTokenCount(response);
                return new AiAlternativeCallResult(result, tokens[0], tokens[1]);
            } catch (AiNonRetryableException e) {
                throw AppException.badRequest("Khong the tao goi y thay the: " + e.getMessage());
            } catch (AiKeyExhaustedException e) {
                aiKeyPool.rotate(apiKey);
                if (lastAttempt) {
                    log.error("Activity alternatives AI failed: moi key deu bi tu choi/het han muc");
                    throw AppException.internal("He thong AI tam het luot. Vui long thu lai sau.");
                }
                log.warn("Key AI bi tu choi (quota/invalid), doi key va thu lai: {}", e.getMessage());
                // doi key → thu lai ngay, khong backoff
            } catch (AiRetryableException | WebClientException e) {
                if (lastAttempt) {
                    log.error("Activity alternatives AI failed after {} attempts", attempt + 1, e);
                    throw AppException.internal("AI khong the tao goi y thay the luc nay. Vui long thu lai sau.");
                }
                sleepBackoff(Math.min(attempt, aiProperties.getMaxRetries()));
            }
        }
        throw AppException.internal("AI alternatives failed");
    }

    private Map<String, Object> buildAiRequest(AlternativeContext context, ActivityAlternativeCategory category) {
        Activity current = context.current();
        Trip trip = context.trip();
        String systemPrompt = """
                Ban la chuyen gia lap lich trinh du lich Viet Nam.
                Nhiem vu cua ban KHONG phai tao lai toan bo lich trinh.
                Ban chi de xuat cac dia diem/hoat dong co the thay the dung 1 activity hien tai trong mot lich trinh da ton tai.

                Quy tac bat buoc:
                - Chi tra ve JSON hop le, khong markdown, khong giai thich ngoai JSON.
                - Tra ve root object co field "alternatives".
                - Moi option phai la dia diem/hoat dong co that tai Viet Nam.
                - searchQuery la chuoi copy vao Google Maps/Goong duoc: ten dia diem cu the + quan/huyen/tinh/thanh.
                - Khong dua dong tu lich trinh vao searchQuery nhu "an toi tai", "tham quan", "di", "check-in".
                - Khong de xuat lai dia diem dang thay the hoac dia diem da co trong lich trinh.
                - Chi thay the activity hien tai, giu nguyen thoi gian, ngay, thu tu va cac activity khac.
                - Chi phi costVnd la tong chi phi cho ca nhom, khong phai moi nguoi.
                - Chi phi nen gan voi activity cu; neu khong can thiet thi khong vuot qua 30-40% so voi activity cu.
                - type chi duoc la FOOD, ATTRACTION, TRANSPORT, ACCOMMODATION, OTHER.
                - Neu activity dang thay la khach san/accommodation, de xuat khach san co the thay the ca block luu tru, khong de xuat quan an/cafe/diem tham quan.
                - Neu nguoi dung chon RESTAURANT hoac CAFE thi type van la FOOD, nhung noi dung phai dung subtype da chon.

                JSON schema:
                {
                  "alternatives": [
                    {
                      "name": "string",
                      "description": "string",
                      "type": "FOOD|ATTRACTION|TRANSPORT|ACCOMMODATION|OTHER",
                      "costVnd": 150000,
                      "searchQuery": "Ten dia diem cu the + tinh/thanh",
                      "reason": "vi sao phu hop de thay the activity hien tai"
                    }
                  ]
                }
                """;

        String userPrompt = String.format("""
                Thong tin chuyen di:
                - Tieu de: %s
                - Khoi hanh: %s
                - Diem den: %s
                - Ngay bat dau: %s
                - Ngay ket thuc: %s
                - So nguoi: %d
                - Ngan sach tong: %,d VND
                - Phong cach: %s

                Activity dang duoc thay the:
                - ID: %d
                - Ngay: %s, ngay so %d
                - Gio: %s
                - Ten: %s
                - Mo ta: %s
                - Type hien tai: %s
                - Chi phi hien tai: %,d VND
                - Search query hien tai: %s
                - Dia chi hien tai: %s

                Nguoi dung muon doi sang nhom: %s
                Target type backend se chap nhan: %s

                Activity truoc/sau trong cung ngay:
                %s

                Tat ca dia diem da co trong lich trinh de tranh trung:
                %s

                Hay tra ve 4 alternatives. Uu tien dia diem gan logic voi gio hien tai, ngan sach, loai chuyen di,
                va khong lam vo mach lich trinh. Neu la FOOD buoi trua/toi thi de xuat quan an phu hop; neu la CAFE thi de xuat cafe;
                neu la ACCOMMODATION thi de xuat khach san/homestay thay the toan bo block luu tru.
                """,
                trip.getTitle(),
                trip.getDeparture(),
                trip.getDestination(),
                trip.getDateStart(),
                trip.getDateEnd(),
                trip.getPeopleCount() != null ? trip.getPeopleCount() : 1,
                trip.getBudgetVnd() != null ? trip.getBudgetVnd() : 0L,
                trip.getStyles(),
                current.getId(),
                context.day().getDate(),
                context.day().getDayNumber(),
                current.getStartTime(),
                current.getName(),
                current.getDescription(),
                current.getType(),
                current.getCostVnd() != null ? current.getCostVnd() : 0L,
                current.getSearchQuery(),
                current.getFormattedAddress(),
                category,
                targetType(category, current.getType()),
                neighborText(context),
                itineraryNamesText(context)
        );

        return Map.of(
                "model", aiProperties.getOpenaiCompat().getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.45
        );
    }

    private List<ActivityAlternativeOptionResponse> buildEnrichedOptions(AlternativeContext context,
                                                                         AiActivityAlternativesResult aiResult,
                                                                         ActivityAlternativeCategory category,
                                                                         int limit) {
        List<AiActivityAlternativesResult.Option> rawOptions =
                aiResult != null && aiResult.getAlternatives() != null ? aiResult.getAlternatives() : List.of();
        if (rawOptions.isEmpty()) return List.of();

        Activity current = context.current();
        ActivityType expectedType = targetType(category, current.getType());
        Set<String> usedKeys = existingPlaceKeys(context);
        List<PlaceEnrichmentService.GeoAnchor> anchors = anchors(context.trip(), expectedType);
        Instant deadline = Instant.now().plusSeconds(35);
        List<AlternativeCandidate> candidates = new ArrayList<>();

        for (AiActivityAlternativesResult.Option raw : rawOptions) {
            ActivityType type = parseType(raw.getType());
            if (!typeAllowed(category, expectedType, type)) continue;

            String name = clean(raw.getName());
            String query = clean(raw.getSearchQuery());
            if (name == null || query == null) continue;

            String key = placeEnrichmentService.canonicalKey(query);
            if (key == null || key.isBlank() || usedKeys.contains(key)) continue;

            Long cost = normalizeCost(raw.getCostVnd());
            if (costTooFar(current.getCostVnd(), cost)) continue;

            usedKeys.add(key);
            candidates.add(new AlternativeCandidate(raw, type, cost));
        }

        if (candidates.isEmpty()) return List.of();

        List<CompletableFuture<ResolvedAlternativeCandidate>> futures = candidates.stream()
                .map(candidate -> CompletableFuture.supplyAsync(() -> {
                    PlaceCache cache = resolvePlace(context, candidate.raw(), candidate.type(), anchors, deadline);
                    if (shouldGeocode(candidate.type()) && cache == null) {
                        return null;
                    }
                    if (cache != null && !resolvedPlaceMatches(candidate.raw(), cache)) {
                        log.warn(
                                "Reject alternative place mismatch: aiName='{}', query='{}', cacheName='{}', serpTitle='{}'",
                                candidate.raw().getName(),
                                candidate.raw().getSearchQuery(),
                                cache.getName(),
                                cache.getSerpTitle()
                        );
                        return null;
                    }
                    Long finalCost = cache != null
                            && candidate.type() == ActivityType.ACCOMMODATION
                            && cache.getPricePerNightVnd() != null
                            ? cache.getPricePerNightVnd()
                            : candidate.cost();
                    if (costTooFar(current.getCostVnd(), finalCost)) return null;
                    return new ResolvedAlternativeCandidate(candidate.raw(), candidate.type(), finalCost, cache);
                }, enrichmentExecutor).exceptionally(ex -> {
                    log.warn("Resolve alternative option failed: {}", ex.getMessage());
                    return null;
                }))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(40, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("Parallel alternative enrichment timeout/error: {}", ex.getMessage());
                    return null;
                })
                .join();

        List<ActivityAlternativeOptionResponse> options = new ArrayList<>();
        Set<String> seenPlaceKeys = new HashSet<>();
        for (CompletableFuture<ResolvedAlternativeCandidate> future : futures) {
            if (options.size() >= limit) break;
            if (!future.isDone() || future.isCompletedExceptionally()) continue;
            ResolvedAlternativeCandidate resolved = future.getNow(null);
            if (resolved == null) continue;
            // Nhiều query AI khác tên có thể cùng resolve về 1 địa điểm Goong (nhất là khi SerpApi
            // rate-limit và Goong gom các tên không tồn tại về cùng toạ độ vùng) → loại trùng theo
            // danh tính địa điểm đã resolve, tránh hiện cùng 1 chỗ thành nhiều option.
            if (!seenPlaceKeys.add(resolvedPlaceKey(resolved))) continue;
            options.add(toOptionResponse(
                    resolved.raw(),
                    resolved.type(),
                    resolved.cost(),
                    resolved.cache(),
                    options.size() + 1));
        }
        return options;
    }

    private PlaceCache resolvePlace(AlternativeContext context,
                                    AiActivityAlternativesResult.Option raw,
                                    ActivityType type,
                                    List<PlaceEnrichmentService.GeoAnchor> anchors,
                                    Instant deadline) {
        if (!shouldGeocode(type) || raw.getSearchQuery() == null || raw.getSearchQuery().isBlank()) {
            return null;
        }
        try {
            // Hướng 2: nhà hàng/điểm tham quan → SerpApi định danh chính xác hơn Goong (tránh trùng địa điểm)
            boolean preferSerp = type == ActivityType.FOOD || type == ActivityType.ATTRACTION;
            Optional<PlaceCache> place = placeEnrichmentService.resolvePlace(
                    raw.getSearchQuery(), context.trip().getDestination(), anchors, deadline, preferSerp);
            place.ifPresent(cache -> {
                if (type == ActivityType.ACCOMMODATION) {
                    LocalDate[] range = accommodationRange(context);
                    placeEnrichmentService.enrichAccommodation(
                            cache,
                            range[0],
                            range[1],
                            context.trip().getPeopleCount(),
                            deadline);
                }
            });
            return place.orElse(null);
        } catch (Exception e) {
            log.warn("Resolve alternative place failed: query='{}', error={}", raw.getSearchQuery(), e.getMessage());
            return null;
        }
    }

    private ActivityAlternativeOptionResponse toOptionResponse(AiActivityAlternativesResult.Option raw,
                                                              ActivityType type,
                                                              Long cost,
                                                              PlaceCache cache,
                                                              int index) {
        return ActivityAlternativeOptionResponse.builder()
                .optionId("opt-" + index)
                .name(displayName(raw, cache))
                .description(clean(raw.getDescription()))
                .type(type)
                .costVnd(cost)
                .searchQuery(displaySearchQuery(raw, cache))
                .reason(clean(raw.getReason()))
                .placeCacheId(cache != null ? cache.getId() : null)
                .address(cache != null ? cache.getAddress() : null)
                .latitude(cache != null ? cache.getLatitude() : null)
                .longitude(cache != null ? cache.getLongitude() : null)
                .rating(cache != null ? cache.getRating() : null)
                .reviewCount(cache != null ? cache.getReviewCount() : null)
                .imageUrl(cache != null ? extractFirstPhotoUrl(cache.getPhotosJson()) : null)
                .bookingUrl(cache != null ? cache.getBookingUrl() : null)
                .openState(cache != null ? cache.getOpenState() : null)
                .build();
    }

    private ChargeResult consumeQuotaOrCredit(Trip trip, Long userId, boolean premium) {
        int limit = effectiveFreeLimit(trip, premium);
        int used = trip.getActivitySwapFreeUsed() != null ? trip.getActivitySwapFreeUsed() : 0;
        if (used < limit) {
            trip.setActivitySwapFreeUsed(used + 1);
            return new ChargeResult(0);
        }
        int updated = userRepository.deductCreditUnitsIfAvailable(userId, PAID_SWAP_UNITS);
        if (updated == 0) {
            throw AppException.insufficientPaid();   // 402 INSUFFICIENT_PAID_CREDITS
        }
        return new ChargeResult(PAID_SWAP_UNITS);
    }

    private List<Activity> replacementTargets(Long tripId, Activity current, ActivityAlternativeOptionResponse selected) {
        if (current.getType() != ActivityType.ACCOMMODATION || selected.getType() != ActivityType.ACCOMMODATION) {
            return List.of(current);
        }
        List<Activity> activities = activityRepository.findByTripIdWithDayOrderByDayAndOrder(tripId);
        List<Activity> targets = activities.stream()
                .filter(activity -> activity.getType() == ActivityType.ACCOMMODATION)
                .filter(activity -> sameAccommodation(current, activity))
                .toList();
        return targets.isEmpty() ? List.of(current) : targets;
    }

    private boolean sameAccommodation(Activity current, Activity candidate) {
        if (current.getId().equals(candidate.getId())) return true;
        if (current.getPlaceCacheId() != null && current.getPlaceCacheId().equals(candidate.getPlaceCacheId())) return true;
        String currentKey = canonicalActivityKey(current);
        String candidateKey = canonicalActivityKey(candidate);
        return !currentKey.isBlank() && currentKey.equals(candidateKey);
    }

    private String canonicalActivityKey(Activity activity) {
        String raw = activity.getSearchQuery() != null && !activity.getSearchQuery().isBlank()
                ? activity.getSearchQuery()
                : activity.getName();
        return raw == null ? "" : placeEnrichmentService.canonicalKey(raw);
    }

    private void applyOption(Activity activity, ActivityAlternativeOptionResponse option, PlaceCache cache) {
        activity.setName(cache != null ? displayName(cache, option.getName()) : option.getName());
        activity.setDescription(option.getDescription());
        activity.setType(option.getType());
        activity.setCostVnd(option.getCostVnd() != null ? option.getCostVnd() : 0L);
        activity.setSearchQuery(cache != null
                ? displaySearchQuery(cache, activity.getName(), option.getSearchQuery())
                : option.getSearchQuery());
        activity.setLatitude(cache != null ? cache.getLatitude() : option.getLatitude());
        activity.setLongitude(cache != null ? cache.getLongitude() : option.getLongitude());
        activity.setPlaceId(cache != null ? cache.getGoongPlaceId() : null);
        activity.setFormattedAddress(cache != null ? cache.getAddress() : option.getAddress());
        activity.setGeocodingProvider(cache != null ? (cache.getGoongPlaceId() != null ? "goong" : "serpapi") : null);
        activity.setPlaceCacheId(cache != null ? cache.getId() : option.getPlaceCacheId());
        activity.setImageUrl(cache != null ? extractFirstPhotoUrl(cache.getPhotosJson()) : option.getImageUrl());
        activity.setBookingUrl(cache != null && cache.getBookingUrl() != null ? cache.getBookingUrl() : option.getBookingUrl());
    }

    private void expireOtherPendingSessions(Long userId, Long tripId, Long keepSessionId, List<Activity> targets) {
        List<Long> activityIds = targets.stream()
                .map(Activity::getId)
                .filter(Objects::nonNull)
                .toList();
        if (activityIds.isEmpty()) return;
        sessionRepository.expireOtherPendingSessionsForActivities(
                userId,
                tripId,
                activityIds,
                keepSessionId,
                ActivityAlternativeSessionStatus.PENDING,
                ActivityAlternativeSessionStatus.EXPIRED);
    }

    private TripDetailResponse.ActivityDetail toActivityDetail(Activity activity) {
        return TripDetailResponse.ActivityDetail.builder()
                .id(activity.getId())
                .startTime(activity.getStartTime())
                .name(activity.getName())
                .description(activity.getDescription())
                .type(activity.getType())
                .costVnd(activity.getCostVnd())
                .latitude(activity.getLatitude())
                .longitude(activity.getLongitude())
                .imageUrl(activity.getImageUrl())
                .bookingUrl(activity.getBookingUrl())
                .displayOrder(activity.getDisplayOrder())
                .placeCacheId(activity.getPlaceCacheId())
                .address(activity.getFormattedAddress())
                .build();
    }

    private Map<String, Object> callLlm(Map<String, Object> body, String apiKey) {
        return aiApiClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.value() == 429 || status.value() == 401 || status.value() == 403,
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new AiKeyExhaustedException(
                                        "AI HTTP " + response.statusCode().value() + ": " + errorBody)))
                .onStatus(status -> status.value() == 400,
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new AiNonRetryableException(
                                        "AI HTTP 400: " + errorBody)))
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new AiRetryableException(
                                        "AI HTTP " + response.statusCode().value() + ": " + errorBody)))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(aiProperties.getTimeoutSeconds()))
                .onErrorMap(TimeoutException.class,
                        e -> new AiRetryableException("AI timeout sau " + aiProperties.getTimeoutSeconds() + "s"))
                .onErrorMap(WebClientRequestException.class,
                        e -> new AiRetryableException("Connection error: " + e.getMessage()))
                .block();
    }

    private AiActivityAlternativesResult parseJson(String content) {
        try {
            return objectMapper.readValue(stripCodeFence(content), AiActivityAlternativesResult.class);
        } catch (JsonProcessingException e) {
            throw new AiRetryableException("JSON parse failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        if (response == null) throw new AiRetryableException("AI tra ve response null");
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) throw new AiRetryableException("AI tra ve choices rong");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) throw new AiRetryableException("AI tra ve message null");
        String content = (String) message.get("content");
        if (content == null || content.isBlank()) throw new AiRetryableException("AI tra ve content rong");
        return content;
    }

    @SuppressWarnings("unchecked")
    private int[] extractTokenCount(Map<String, Object> response) {
        try {
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            int prompt = ((Number) usage.get("prompt_tokens")).intValue();
            int completion = ((Number) usage.get("completion_tokens")).intValue();
            return new int[]{prompt, completion};
        } catch (RuntimeException e) {
            return new int[]{0, 0};
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep((long) (Math.pow(2, attempt) * 500));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw AppException.internal("Tao goi y thay the bi gian doan");
        }
    }

    private String neighborText(AlternativeContext context) {
        List<Activity> sameDay = context.allActivities().stream()
                .filter(activity -> activity.getDay().getId().equals(context.day().getId()))
                .toList();
        int idx = -1;
        for (int i = 0; i < sameDay.size(); i++) {
            if (sameDay.get(i).getId().equals(context.current().getId())) {
                idx = i;
                break;
            }
        }
        String previous = idx > 0 ? activityLine(sameDay.get(idx - 1)) : "Khong co";
        String next = idx >= 0 && idx < sameDay.size() - 1 ? activityLine(sameDay.get(idx + 1)) : "Khong co";
        return "- Truoc: " + previous + "\n- Sau: " + next;
    }

    private Optional<Activity> previousSameDayActivity(AlternativeContext context) {
        List<Activity> sameDay = context.allActivities().stream()
                .filter(activity -> activity.getDay() != null)
                .filter(activity -> Objects.equals(activity.getDay().getId(), context.day().getId()))
                .toList();
        for (int i = 0; i < sameDay.size(); i++) {
            if (Objects.equals(sameDay.get(i).getId(), context.current().getId())) {
                return i > 0 ? Optional.of(sameDay.get(i - 1)) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private String itineraryNamesText(AlternativeContext context) {
        StringBuilder builder = new StringBuilder();
        for (Activity activity : context.allActivities()) {
            builder.append("- Ngay ")
                    .append(activity.getDay().getDayNumber())
                    .append(" ")
                    .append(activity.getStartTime())
                    .append(" | ")
                    .append(activity.getType())
                    .append(" | ")
                    .append(activity.getName())
                    .append(" | query=")
                    .append(activity.getSearchQuery())
                    .append('\n');
        }
        return builder.toString();
    }

    private String activityLine(Activity activity) {
        return activity.getStartTime() + " | " + activity.getType() + " | " + activity.getName();
    }

    private Set<String> existingPlaceKeys(AlternativeContext context) {
        Set<String> keys = new HashSet<>();
        for (Activity activity : context.allActivities()) {
            if (activity.getSearchQuery() != null && !activity.getSearchQuery().isBlank()) {
                keys.add(placeEnrichmentService.canonicalKey(activity.getSearchQuery()));
            } else if (activity.getName() != null && !activity.getName().isBlank()) {
                keys.add(placeEnrichmentService.canonicalKey(activity.getName()));
            }
        }
        return keys;
    }

    /** Khóa danh tính địa điểm đã resolve để loại option trùng (ưu tiên goongPlaceId → serpPlaceId → serpDataId → placeCacheId → query). */
    private String resolvedPlaceKey(ResolvedAlternativeCandidate resolved) {
        PlaceCache cache = resolved.cache();
        if (cache != null) {
            if (cache.getGoongPlaceId() != null && !cache.getGoongPlaceId().isBlank()) {
                return "goong:" + cache.getGoongPlaceId();
            }
            // Row định danh bằng SerpApi (goongPlaceId=null): dedup theo serpPlaceId, rồi serpDataId
            // (candidate local_results có thể thiếu place_id nhưng vẫn có data_id) để 2 query khác tên
            // cùng trỏ về 1 địa điểm SerpApi không hiện thành 2 option
            if (cache.getSerpPlaceId() != null && !cache.getSerpPlaceId().isBlank()) {
                return "serp:" + cache.getSerpPlaceId();
            }
            if (cache.getSerpDataId() != null && !cache.getSerpDataId().isBlank()) {
                return "serpdata:" + cache.getSerpDataId();
            }
            if (cache.getId() != null) {
                return "cache:" + cache.getId();
            }
        }
        String raw = resolved.raw().getSearchQuery() != null && !resolved.raw().getSearchQuery().isBlank()
                ? resolved.raw().getSearchQuery()
                : resolved.raw().getName();
        return "q:" + placeEnrichmentService.canonicalKey(raw == null ? "" : raw);
    }

    private List<PlaceEnrichmentService.GeoAnchor> anchors(Trip trip, ActivityType targetType) {
        List<PlaceEnrichmentService.GeoAnchor> anchors = new ArrayList<>(2);
        placeEnrichmentService.geocodeAnchor(trip.getDestination()).ifPresent(anchors::add);
        if (targetType == ActivityType.TRANSPORT) {
            placeEnrichmentService.geocodeAnchor(trip.getDeparture()).ifPresent(anchors::add);
        }
        return anchors;
    }

    private LocalDate[] accommodationRange(AlternativeContext context) {
        if (context.current().getType() != ActivityType.ACCOMMODATION) {
            LocalDate checkIn = context.day().getDate() != null ? context.day().getDate() : context.trip().getDateStart();
            return new LocalDate[]{checkIn, checkIn.plusDays(1)};
        }
        List<Activity> block = context.allActivities().stream()
                .filter(activity -> activity.getType() == ActivityType.ACCOMMODATION)
                .filter(activity -> sameAccommodation(context.current(), activity))
                .toList();
        LocalDate checkIn = block.stream()
                .map(activity -> activity.getDay().getDate())
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(context.trip().getDateStart());
        LocalDate checkOut = block.stream()
                .map(activity -> activity.getDay().getDate())
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .map(date -> date.plusDays(1))
                .orElse(context.trip().getDateEnd());
        if (!checkOut.isAfter(checkIn)) {
            checkOut = checkIn.plusDays(1);
        }
        return new LocalDate[]{checkIn, checkOut};
    }

    private ActivityType targetType(ActivityAlternativeCategory category, ActivityType currentType) {
        return switch (category) {
            case HOTEL -> ActivityType.ACCOMMODATION;
            case RESTAURANT, CAFE -> ActivityType.FOOD;
            case ATTRACTION -> ActivityType.ATTRACTION;
            case TRANSPORT -> ActivityType.TRANSPORT;
            case SAME_TYPE -> currentType != null ? currentType : ActivityType.OTHER;
        };
    }

    private boolean typeAllowed(ActivityAlternativeCategory category, ActivityType expectedType, ActivityType actualType) {
        if (actualType != expectedType) return false;
        if (category == ActivityAlternativeCategory.SAME_TYPE) return true;
        return expectedType != ActivityType.OTHER;
    }

    private boolean shouldGeocode(ActivityType type) {
        return type == ActivityType.FOOD
                || type == ActivityType.ATTRACTION
                || type == ActivityType.ACCOMMODATION
                || type == ActivityType.TRANSPORT;
    }

    private ActivityType parseType(String value) {
        if (value == null) return ActivityType.OTHER;
        try {
            return ActivityType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ActivityType.OTHER;
        }
    }

    private Long normalizeCost(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private boolean costTooFar(Long currentCost, Long optionCost) {
        long current = currentCost != null ? currentCost : 0L;
        long option = optionCost != null ? optionCost : 0L;
        if (current <= 0 || option <= 0) return false;
        return option > Math.round(current * 1.5);
    }

    /**
     * Quota đổi free hiệu lực = max(snapshot lúc tạo, premium hiện tại ? 4 : 0).
     * → User nạp gói được 4 lượt free ngay cả trên chuyến tạo lúc còn Normal (limit snapshot=0),
     * tránh trừ credit vừa nạp ngay từ lượt đầu. Chuyến Premium giữ snapshot 4 kể cả khi paid về 0.
     */
    private int effectiveFreeLimit(Trip trip, boolean premium) {
        int snapshot = trip.getActivitySwapFreeLimit() != null ? trip.getActivitySwapFreeLimit() : 0;
        return Math.max(snapshot, premium ? DEFAULT_LIMIT : 0);
    }

    private int freeSwapsRemaining(Trip trip, boolean premium) {
        int used = trip.getActivitySwapFreeUsed() != null ? trip.getActivitySwapFreeUsed() : 0;
        return Math.max(0, effectiveFreeLimit(trip, premium) - used);
    }

    private int chargeUnitsIfApplied(Trip trip, boolean premium) {
        return freeSwapsRemaining(trip, premium) > 0 ? 0 : PAID_SWAP_UNITS;
    }

    private BigDecimal unitsToCredits(int units) {
        return BigDecimal.valueOf(units)
                .divide(BigDecimal.valueOf(CREDIT_UNIT_SCALE), 2, RoundingMode.DOWN)
                .stripTrailingZeros();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        return Math.max(1, Math.min(DEFAULT_LIMIT, limit));
    }

    private String writeOptions(List<ActivityAlternativeOptionResponse> options) {
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw AppException.internal("Khong the luu goi y thay the");
        }
    }

    private List<ActivityAlternativeOptionResponse> readOptions(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ActivityAlternativeOptionResponse>>() {});
        } catch (JsonProcessingException e) {
            throw AppException.internal("Khong the doc goi y thay the");
        }
    }

    private String extractFirstPhotoUrl(String photosJson) {
        if (photosJson == null || photosJson.isBlank()) return null;
        try {
            List<Map<String, String>> raw = objectMapper.readValue(photosJson, new TypeReference<>() {});
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

    private boolean resolvedPlaceMatches(AiActivityAlternativesResult.Option raw, PlaceCache cache) {
        if (cache == null) return false;

        String requestedName = clean(raw.getName());
        String requestedQuery = clean(raw.getSearchQuery());
        String resolvedName = clean(cache.getSerpTitle()) != null ? cache.getSerpTitle() : cache.getName();

        if (normalizedContains(resolvedName, requestedName) || normalizedContains(resolvedName, requestedQuery)) {
            return true;
        }

        Set<String> requestedTokens = meaningfulTokens(joinForMatch(requestedName, requestedQuery));
        String resolvedTokenSource = clean(cache.getSerpTitle()) != null
                ? joinForMatch(cache.getSerpTitle(), cache.getAddress())
                : joinForMatch(cache.getName(), cache.getAddress());
        Set<String> resolvedTokens = meaningfulTokens(resolvedTokenSource);
        if (requestedTokens.isEmpty() || resolvedTokens.isEmpty()) return false;

        long overlap = requestedTokens.stream().filter(resolvedTokens::contains).count();
        double score = (double) overlap / Math.min(requestedTokens.size(), resolvedTokens.size());
        return overlap >= 1 && score >= 0.34d;
    }

    private String displayName(AiActivityAlternativesResult.Option raw, PlaceCache cache) {
        return displayName(cache, clean(raw.getName()));
    }

    private String displayName(PlaceCache cache, String fallback) {
        if (cache != null) {
            String serpTitle = clean(cache.getSerpTitle());
            if (serpTitle != null) return serpTitle;
            String cacheName = clean(cache.getName());
            if (cacheName != null) return cacheName;
        }
        return clean(fallback);
    }

    private String displaySearchQuery(AiActivityAlternativesResult.Option raw, PlaceCache cache) {
        return displaySearchQuery(cache, displayName(raw, cache), clean(raw.getSearchQuery()));
    }

    private String displaySearchQuery(PlaceCache cache, String name, String fallbackQuery) {
        if (cache == null) return clean(fallbackQuery);

        String address = clean(cache.getAddress());
        if (name == null) return address;
        if (address == null) return name;
        if (normalizedContains(address, name)) return truncate(address, MAX_ACTIVITY_SEARCH_QUERY_LENGTH);
        return truncate(name + ", " + address, MAX_ACTIVITY_SEARCH_QUERY_LENGTH);
    }

    private boolean normalizedContains(String haystack, String needle) {
        String normalizedHaystack = normalizeForMatch(haystack);
        String normalizedNeedle = normalizeForMatch(needle);
        return normalizedNeedle != null
                && normalizedHaystack != null
                && !normalizedNeedle.isBlank()
                && normalizedHaystack.contains(normalizedNeedle);
    }

    private Set<String> meaningfulTokens(String value) {
        String normalized = normalizeForMatch(value);
        if (normalized == null || normalized.isBlank()) return Set.of();
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() < 2) continue;
            if (GENERIC_PLACE_TOKENS.contains(token)) continue;
            tokens.add(token);
        }
        return tokens;
    }

    private String joinForMatch(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            String cleaned = clean(value);
            if (cleaned == null) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(cleaned);
        }
        return builder.toString();
    }

    private String normalizeForMatch(String value) {
        String cleaned = clean(value);
        if (cleaned == null) return null;
        String noAccent = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noAccent.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stripCodeFence(String content) {
        String value = content.trim();
        if (!value.startsWith("```")) return value;
        int firstNewline = value.indexOf('\n');
        if (firstNewline >= 0) value = value.substring(firstNewline + 1);
        if (value.endsWith("```")) value = value.substring(0, value.length() - 3);
        return value.trim();
    }

    private record AlternativeContext(Trip trip, TripDay day, Activity current, List<Activity> allActivities) {}
    private record AiAlternativeCallResult(AiActivityAlternativesResult result, int promptTokens, int completionTokens) {}
    private record AlternativeCandidate(AiActivityAlternativesResult.Option raw, ActivityType type, Long cost) {}
    private record ResolvedAlternativeCandidate(
            AiActivityAlternativesResult.Option raw,
            ActivityType type,
            Long cost,
            PlaceCache cache) {}
    private record ChargeResult(int chargedUnits) {}

    private static class AiRetryableException extends RuntimeException {
        AiRetryableException(String message) { super(message); }
    }

    private static class AiNonRetryableException extends RuntimeException {
        AiNonRetryableException(String message) { super(message); }
    }

    /** Key bị từ chối (429 quota / 401-403 invalid) → xoay sang key khác rồi thử lại. */
    private static class AiKeyExhaustedException extends RuntimeException {
        AiKeyExhaustedException(String message) { super(message); }
    }
}
