# ChipTrip — AI Trip Generation Integration

## Tổng quan

Tích hợp AI thật vào chức năng tạo lịch trình `POST /api/v1/trips/generate`.  
Trước đây toàn bộ dữ liệu lịch trình là mock (hardcode tên địa điểm, tọa độ cố định, Random).  
Sau khi integrate: AI sinh lịch trình thật → Backend geocode tọa độ thật → Lưu DB → Trả response.

---

## Luồng đầy đủ

```
Frontend (React)
  └─ handleGenerate(): map budget index → VNĐ thật, đổi tên field departure/budgetVnd
  └─ POST /api/v1/trips/generate
       {
         departure: "Hà Nội",
         destination: "Đà Lạt",
         startDate: "2026-06-10",
         endDate: "2026-06-12",
         peopleCount: 2,
         budgetVnd: 4000000,
         styles: ["food", "healing"]
       }

Backend — TripServiceImpl.generateTrip()  (KHÔNG @Transactional — Propagation.NOT_SUPPORTED)
  ├─ 1. validateRequestAndCredits (read-only tx ngắn)
  │       - findById(user); throw badRequest nếu aiCredits <= 0
  │       - startDate >= today, endDate >= startDate
  │
  ├─ 2. AiService.generateItinerary(request)  (NGOÀI transaction — tránh giữ DB connection)
  │       └─ AiGenerateServiceImpl
  │             ├─ Build system prompt + user prompt (tiếng Việt)
  │             ├─ Build responseSchema strict (bắt AI trả đúng format)
  │             ├─ POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent
  │             │    responseMimeType: application/json, responseSchema, thinkingConfig.thinkingBudget=0
  │             ├─ Parse JSON → AiItineraryResult
  │             ├─ Pre-validate trong parseAndValidate: days không rỗng, time+name, searchQuery cho type geocodable
  │             ├─ Retry tối đa max-retries (mặc định 2) nếu: 429 / 5xx / timeout / parse fail / validate fail
  │             ├─ 400/401/403 → throw AppException.badRequest, KHÔNG retry
  │             └─ Trả AiCallResult { itinerary, promptTokens, completionTokens }
  │
  ├─ 3. AiItineraryValidator.validate(itinerary, request)  (fail-fast strict)
  │       - Đủ số ngày, dayNumber duy nhất, date trong [startDate, endDate]
  │       - Activity hợp lệ: type, time HH:mm, costVnd ≥ 0
  │       - Type geocodable phải có searchQuery, KHÔNG generic (vd "khách sạn trung tâm")
  │       - Tổng cost ≤ budget × 1.10 hoặc ≤ budget + 1.000.000đ
  │
  ├─ 4. resolvePlaces  (HTTP calls, fail-soft per activity)
  │       ├─ Geocode destination/departure thành GeoAnchor để validate vùng theo GPS
  │       ├─ Dedup geocodable activity theo canonicalKey(searchQuery) trước khi fan-out
  │       └─ Với mỗi group geocodable:
  │           ├─ PlaceEnrichmentService.resolvePlace(searchQuery, destination, anchors, deadline)
  │           ├─ PlaceCache lookup theo normalizedName + normalizedDestination
  │           ├─ Cache miss → GoongClient.forwardGeocode V2 (has_vnid)
  │           │     → lat/lng + placeId + formattedAddress + provinceName/communeName
  │           │     → validate theo address/GeoAnchor/province; miss/mismatch thì fallback SerpApi
  │           ├─ SerpApi enrich (engine google_maps/google_maps_photos/google_maps_reviews)
  │           │     → rating, ảnh, giờ mở cửa, reviews → lưu vào PlaceCache
  │           ├─ Nếu ACCOMMODATION: enrichAccommodation gọi SerpApi google_hotels
  │           │     → pricePerNightVnd + bookingUrl theo check-in/out của group khách sạn
  │           └─ Exception cho 1 group → log warn, skip group, trip vẫn tạo
  │
  ▼
TripGenerationPersistenceServiceImpl.persistGeneratedTrip   (@Transactional — 1 tx)
  ├─ Re-load user + RE-CHECK aiCredits (chống race giữa các generate request)
  ├─ Save Trip, seedOwner (TripMember OWNER)
  ├─ Với mỗi AiDay → save TripDay
  │   └─ Với mỗi AiActivity:
  │         ├─ Lấy PlaceCache từ resolvedPlaces map (identity-keyed)
  │         ├─ Set lat/lng, placeId, formattedAddress, geocodingProvider ("goong" hoặc "serpapi"), placeCacheId
  │         ├─ Nếu ACCOMMODATION & PlaceCache.pricePerNightVnd != null → override costVnd
  │         ├─ Ưu tiên PlaceCache.bookingUrl (đã có check_in/check_out) hơn aiActivity.bookingUrl
  │         ├─ imageUrl = ảnh đầu từ PlaceCache.photosJson
  │         └─ Save Activity
  ├─ Save ChecklistItem
  ├─ ★ TRỪ user.aiCredits SAU khi persist thành công ★
  │     - Nếu Gemini/validate/persist throw → KHÔNG mất lượt
  │     - remainingCredits <= 1 → publishEvent(AiCreditsLowEvent) → noti listener
  ├─ Log AiUsage { provider="gemini", tokensIn/out, costUsd theo pricing config }
  └─ Trả TripGenerateResponse (id, title, days, activities, checklist, totalCostVnd)

Frontend — Result page
  └─ Render lịch trình từ response
```

---

## Cấu trúc file đã thay đổi

### File mới — Backend

```
src/main/java/com/tranbac/chiptripbe/
├── common/config/
│   ├── AiProperties.java              — @ConfigurationProperties("app.ai")
│   ├── GeocodingProperties.java       — @ConfigurationProperties("app.geocoding") (provider selection)
│   ├── GoongProperties.java           — @ConfigurationProperties("app.goong")
│   └── SerpApiProperties.java         — @ConfigurationProperties("app.serpapi")
│
├── module/ai/
│   ├── dto/
│   │   ├── AiItineraryResult.java     — Internal DTO parse JSON từ Gemini
│   │   └── AiCallResult.java          — Wrapper: itinerary + promptTokens + completionTokens
│   └── service/
│       ├── AiService.java             — Interface: generateItinerary(request) → AiCallResult
│       └── impl/
│           └── AiGenerateServiceImpl.java  — WebClient → Gemini, retry logic, validate
│
└── module/geocoding/
    ├── dto/
    │   └── GeocodingResult.java       — Record: placeId, name, formattedAddress, lat, lng, provider
    ├── client/
    │   ├── GoongClient.java           — HTTP client → Goong REST API
    │   └── SerpApiClient.java         — HTTP client → SerpApi (enrichment)
    └── service/
        ├── GeocodingService.java      — Interface: searchPlace(query, destination)
        └── impl/
            └── GoongGeocodingService.java  — Goong geocoding (PlaceCache + SerpApi enrichment)
```

### File sửa — Backend

| File | Thay đổi chính |
|---|---|
| `module/ai/dto/AiItineraryResult.java` | `AiActivity`: bỏ `latitude/longitude`, thêm `searchQuery` |
| `module/ai/service/impl/AiGenerateServiceImpl.java` | Schema, prompt, validation searchQuery |
| `module/trip/entity/Activity.java` | Thêm: `searchQuery`, `placeId`, `formattedAddress`, `geocodingProvider`, `placeCacheId` |
| `module/trip/service/impl/TripServiceImpl.java` | Xóa mock; inject AiService + PlaceEnrichmentService; log AiUsage |
| `common/exception/AppException.java` | Thêm `internal()` factory |
| `src/main/resources/application.yml` | Thêm `app.ai.*`, `app.goong.*`, `app.serpapi.*` |
| `.env.example` | Document `GEMINI_API_KEY`, `GOONG_API_KEY`, `SERPAPI_API_KEY` |

### File sửa — Frontend

| File | Thay đổi chính |
|---|---|
| `src/integrations/api/modules/trips.ts` | Đổi `origin→departure`, `budget:number→budgetVnd:number` |
| `src/app/pages/Planning.tsx` | Map budget index→VNĐ (`BUDGET_VND_MAP`), fix `handleGenerate` + `handleSuggest` |

---

## Cấu hình môi trường (.env)

```env
# Database
DB_URL=jdbc:sqlserver://<host>:1433;databaseName=chiptrip;encrypt=true;trustServerCertificate=true
DB_USERNAME=
DB_PASSWORD=

# JWT
JWT_SECRET=

# Gemini — bắt buộc cho AI generate
GEMINI_API_KEY=AIza...

# Goong — bắt buộc cho geocoding + autocomplete (Goong REST API)
GOONG_API_KEY=
GOONG_MAP_TOKEN=

# SerpApi — enrichment (rating, giờ mở cửa, ảnh); để trống nếu không dùng
SERPAPI_API_KEY=
```

---

## Cấu hình AI & Geocoding (application.yml)

```yaml
app:
  ai:
    gemini:
      api-key: ${GEMINI_API_KEY}
      model: gemini-2.5-flash
      base-url: https://generativelanguage.googleapis.com/v1beta
    max-retries: 2
    timeout-seconds: 60
    pricing:
      input-usd-per-1m: 0.075
      output-usd-per-1m: 0.30
  goong:
    api-key: ${GOONG_API_KEY}
    base-url: https://rsapi.goong.io
    timeout-seconds: 5
  serpapi:
    api-key: ${SERPAPI_API_KEY:}
    base-url: https://serpapi.com
    timeout-seconds: 10
    enabled: true
    cache-ttl-days: 30
```

---

## Schema JSON AI trả về

```json
{
  "title": "Hành trình Đà Lạt 3 ngày 2 đêm",
  "days": [
    {
      "dayNumber": 1,
      "date": "2026-06-10",
      "activities": [
        {
          "time": "07:00",
          "name": "Bánh căn Nhà Chung",
          "description": "Ăn sáng đặc sản bánh căn Đà Lạt",
          "type": "FOOD",
          "costVnd": 50000,
          "searchQuery": "Bánh căn Nhà Chung Đà Lạt",
          "bookingUrl": null
        }
      ]
    }
  ],
  "checklist": [
    { "category": "PAPERS", "name": "CMND/CCCD" },
    { "category": "CLOTHES", "name": "Áo ấm" }
  ]
}
```

**ActivityType enum** (dùng trong schema và entity):  
`FOOD` · `ATTRACTION` · `TRANSPORT` · `ACCOMMODATION` · `OTHER`

**ChecklistCategory enum**:  
`PAPERS` · `CLOTHES` · `HYGIENE` · `OTHER`

---

## Logic geocode

| ActivityType | Gọi Goong? |
|---|---|
| `FOOD` | Có |
| `ATTRACTION` | Có |
| `ACCOMMODATION` | Có |
| `TRANSPORT` | **Có** |
| `OTHER` | Không |

> **Sửa drift:** TRANSPORT cần geocode để có lat/lng cho sân bay / bến xe / nhà ga (vd "Sân bay Liên Khương Đà Lạt"). Xác minh ở 3 chỗ trong code, tất cả đều bao gồm TRANSPORT:
> - `TripServiceImpl.shouldGeocode()` (line 425)
> - `AiGenerateServiceImpl.isGeocodableType()` (line 295)
> - `AiItineraryValidator.needsSearchQuery()` (line 162)

- Nếu Goong trả về kết quả đúng vùng → lưu `latitude`, `longitude`, `placeId`, `formattedAddress`, `geocodingProvider = "goong"`, `placeCacheId`
- Nếu Goong miss hoặc sai vùng → thử SerpApi fallback; nếu candidate đúng vùng thì lưu lat/lng và `geocodingProvider = "serpapi"` với `placeId = null`
- SerpApi enrich thêm rating / giờ mở cửa / ảnh / reviews vào `PlaceCache` (TTL `cache-ttl-days`, backoff `retry-backoff-minutes`)
- Với `ACCOMMODATION`: gọi thêm SerpApi `google_hotels` lấy `pricePerNightVnd` + `bookingUrl` theo check-in/check-out của group khách sạn
- Nếu không tìm thấy hoặc lỗi → `latitude/longitude = null`, không throw exception (graceful fallback per-activity)
- Query gửi đi: `PlaceQueryUtil.buildPlaceQuery(searchQuery)`; validation vùng dựa trên destination/departure anchors và normalized destination

---

## Retry & Error handling

### AI (Gemini)
| Trường hợp | Hành vi |
|---|---|
| 400 Bad Request | Không retry → throw `AppException.badRequest` |
| 401 / 403 | Không retry → throw `AppException.badRequest` (API key sai) |
| 429 / 5xx | Retry tối đa `max-retries` lần |
| Timeout (60s) | Retry |
| JSON parse fail | Retry |
| Validate fail (days rỗng, thiếu searchQuery...) | Retry |
| Hết retry | Throw `AppException.internal` |

### Geocoding (Goong + SerpApi)
| Trường hợp | Hành vi |
|---|---|
| Không tìm thấy địa điểm | Trả `Optional.empty()`, lat/lng = null |
| Timeout (5s) | Bắt exception, log warn, trả `Optional.empty()` |
| Lỗi HTTP bất kỳ | Bắt exception, log warn, trả `Optional.empty()` |

---

## AiUsage logging

Sau mỗi lần generate thành công, lưu vào bảng `ai_usages`:

| Field | Giá trị |
|---|---|
| `provider` | `"gemini"` |
| `tokensIn` | `usageMetadata.promptTokenCount` từ response |
| `tokensOut` | `usageMetadata.candidatesTokenCount` từ response |
| `costUsd` | `tokensIn/1M * 0.075 + tokensOut/1M * 0.30` |
| `userId` | ID người dùng gọi API |
| `tripId` | ID trip vừa tạo |

---

## Budget mapping (Frontend)

Frontend gửi `budgetVnd` là số VNĐ thật. Mapping từ index preset:

| Index | Label | VNĐ gửi đi |
|---|---|---|
| 0 | < 500K | 400,000 |
| 1 | 500K–1M | 750,000 |
| 2 | 1–2M | 1,500,000 |
| 3 | 2–3M | 2,500,000 |
| 4 | 3–5M | 4,000,000 |
| 5 | 5–8M | 6,500,000 |
| 6 | 8–12M | 10,000,000 |
| 7 | 12M+ | 15,000,000 |

Nếu user nhập custom → parse số trực tiếp (bỏ ký tự không phải chữ số).

---

## Changelog đồng bộ

Sửa ngày 2026-06-09 để khớp với code thực tế.

| Trước → Sau | Căn cứ (file code) |
|---|---|
| Sơ đồ luồng generate cũ: "Check aiCredits", "Tạo Trip, save DB", "Trừ aiCredits (sau khi persist thành công)" mô tả flat — không thể hiện việc orchestration KHÔNG @Transactional và việc trừ credit nằm trong service persist riêng | `TripServiceImpl.java` line 84 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`; `TripGenerationPersistenceServiceImpl.java` line 62 `@Transactional`, line 89-95 trừ credit + publish event sau khi persist |
| Thiếu bước **AiItineraryValidator.validate** (validator riêng strict bên ngoài AiGenerateServiceImpl) | `module/ai/service/AiItineraryValidator.java` (toàn file) |
| Bảng "Logic geocode" **TRANSPORT = Không** → **TRANSPORT = Có** | `TripServiceImpl.shouldGeocode()` line 425-430, `AiGenerateServiceImpl.isGeocodableType()` line 295-298, `AiItineraryValidator.needsSearchQuery()` line 162-168 — cả 3 đều include TRANSPORT |
| Mô tả enrichment chỉ nhắc "rating / giờ mở cửa / ảnh" → bổ sung **reviews** + đặc biệt **SerpApi google_hotels cho ACCOMMODATION** (pricePerNightVnd + bookingUrl) | `PlaceCache.java` field `reviewsJson`, `pricePerNightVnd`, `bookingUrl`; `TripGenerationPersistenceServiceImpl.persistDay` line 186-200 ưu tiên `PlaceCache.bookingUrl` |
| Mô tả "Cấu hình môi trường" bỏ `GOONG_MAP_TOKEN` không tham chiếu trong backend (chỉ FE dùng) | `application.yml` không có entry `goong.map-token`; backend chỉ dùng `${GOONG_API_KEY}` |

Sơ đồ JSON schema và mục "Schema JSON AI trả về" đã đúng (`costVnd`, `searchQuery`, không có `lat`/`lng`) — đối chiếu với `AiGenerateServiceImpl.buildResponseSchema()`.
