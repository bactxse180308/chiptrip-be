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

Backend — TripServiceImpl.generateTrip()
  ├─ 1. Validate user tồn tại
  ├─ 2. Check aiCredits > 0
  ├─ 3. Validate startDate >= today, endDate > startDate
  │
  ├─ 4. AiService.generateItinerary(request)
  │       └─ AiGenerateServiceImpl
  │             ├─ Build system prompt + user prompt (tiếng Việt)
  │             ├─ Build responseSchema strict (bắt AI trả đúng format)
  │             ├─ POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent
  │             │    responseMimeType: application/json, responseSchema, thinkingConfig.thinkingBudget=0
  │             ├─ Parse JSON → AiItineraryResult
  │             ├─ Validate: days không rỗng, activity có time+name+searchQuery
  │             ├─ Retry tối đa 2 lần nếu: 429 / 5xx / timeout / parse fail / validate fail
  │             └─ Trả AiCallResult { itinerary, promptTokens, completionTokens }
  │
  ├─ 5. Tạo Trip entity, save DB
  │
  ├─ 6. Với mỗi AiDay → TripDay entity
  │       └─ Với mỗi AiActivity:
  │             ├─ Parse ActivityType (FOOD/ATTRACTION/TRANSPORT/ACCOMMODATION/OTHER)
  │             ├─ Nếu type là FOOD / ATTRACTION / ACCOMMODATION:
  │             │    └─ PlaceEnrichmentService.resolvePlace(searchQuery, destination)
  │             │         └─ GoongGeocodingService (GoongClient) + SerpApiClient (enrichment)
  │             │               ├─ GET https://rsapi.goong.io/geocode (hoặc /Place/AutoComplete)
  │             │               │    address: "Bánh căn Nhà Chung Đà Lạt, Đà Lạt"
  │             │               ├─ Parse kết quả đầu tiên → PlaceCache (lat/lng, address, placeId)
  │             │               ├─ SerpApi enrich: rating, giờ mở cửa, ảnh (cache PlaceCache)
  │             │               └─ Nếu fail/không tìm thấy → Optional.empty() (graceful)
  │             ├─ Activity.latitude/longitude = từ PlaceCache (hoặc null)
  │             ├─ Activity.placeId, formattedAddress, geocodingProvider = "goong", placeCacheId, imageUrl
  │             └─ Save Activity
  │
  ├─ 7. Với mỗi AiChecklistItem → ChecklistItem entity, save DB
  │
  ├─ 8. Trừ user.aiCredits (sau khi persist thành công)
  │
  ├─ 9. Log AiUsage { provider="gemini", tokensIn, tokensOut, costUsd }
  │
  └─ 10. Trả TripGenerateResponse (id, title, days, activities, checklist, totalCostVnd)

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
| `TRANSPORT` | Không |
| `OTHER` | Không |

- Nếu Goong trả về kết quả → lưu `latitude`, `longitude`, `placeId`, `formattedAddress`, `geocodingProvider = "goong"`, `placeCacheId`
- SerpApi enrich thêm rating / giờ mở cửa / ảnh và cache vào `PlaceCache` (TTL `cache-ttl-days`)
- Nếu không tìm thấy hoặc lỗi → `latitude/longitude = null`, không throw exception (graceful fallback)
- Query gửi đi: `"<searchQuery>, <destination>"` (append destination nếu chưa có trong query)

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
