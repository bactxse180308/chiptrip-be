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
  │             ├─ Build json_schema strict (bắt AI trả đúng format)
  │             ├─ POST https://api.openai.com/v1/chat/completions
  │             │    model: gpt-4o-mini, response_format: json_schema
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
  │             │    └─ GeocodingService.searchPlace(searchQuery, destination)
  │             │         └─ GooglePlacesGeocodingService
  │             │               ├─ POST https://places.googleapis.com/v1/places:searchText
  │             │               │    textQuery: "Bánh căn Nhà Chung Đà Lạt, Đà Lạt, Việt Nam"
  │             │               ├─ Parse place đầu tiên → GeocodingResult
  │             │               └─ Nếu fail/không tìm thấy → null (graceful)
  │             ├─ Activity.latitude/longitude = từ GeocodingResult (hoặc null)
  │             ├─ Activity.placeId, formattedAddress, geocodingProvider = "google"
  │             └─ Save Activity
  │
  ├─ 7. Với mỗi AiChecklistItem → ChecklistItem entity, save DB
  │
  ├─ 8. Trừ user.aiCredits (sau khi persist thành công)
  │
  ├─ 9. Log AiUsage { provider="openai", tokensIn, tokensOut, costUsd }
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
│   └── GeocodingProperties.java       — @ConfigurationProperties("app.geocoding")
│
├── module/ai/
│   ├── dto/
│   │   ├── AiItineraryResult.java     — Internal DTO parse JSON từ OpenAI
│   │   └── AiCallResult.java          — Wrapper: itinerary + promptTokens + completionTokens
│   └── service/
│       ├── AiService.java             — Interface: generateItinerary(request) → AiCallResult
│       └── impl/
│           └── AiGenerateServiceImpl.java  — WebClient → OpenAI, retry logic, validate
│
└── module/geocoding/
    ├── dto/
    │   └── GeocodingResult.java       — Record: placeId, name, formattedAddress, lat, lng, provider
    └── service/
        ├── GeocodingService.java      — Interface: searchPlace(query, destination)
        └── impl/
            └── GooglePlacesGeocodingService.java  — WebClient → Google Places API New
```

### File sửa — Backend

| File | Thay đổi chính |
|---|---|
| `module/ai/dto/AiItineraryResult.java` | `AiActivity`: bỏ `latitude/longitude`, thêm `searchQuery` |
| `module/ai/service/impl/AiGenerateServiceImpl.java` | Schema, prompt, validation searchQuery |
| `module/trip/entity/Activity.java` | Thêm: `searchQuery`, `placeId`, `formattedAddress`, `geocodingProvider` |
| `module/trip/service/impl/TripServiceImpl.java` | Xóa mock; inject AiService + GeocodingService; log AiUsage |
| `common/exception/AppException.java` | Thêm `internal()` factory |
| `src/main/resources/application.yml` | Thêm `app.ai.*` và `app.geocoding.*` |
| `.env.example` | Document `OPENAI_API_KEY`, `GOOGLE_MAPS_API_KEY` |

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

# OpenAI — bắt buộc cho AI generate
OPENAI_API_KEY=sk-...

# Google Maps — bắt buộc cho geocoding (Places API New)
GOOGLE_MAPS_API_KEY=AIza...
```

---

## Cấu hình AI & Geocoding (application.yml)

```yaml
app:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o-mini
      base-url: https://api.openai.com/v1
    max-retries: 2
    timeout-seconds: 60
    pricing:
      input-usd-per-1m: 0.15
      output-usd-per-1m: 0.60
  geocoding:
    provider: google
    google:
      api-key: ${GOOGLE_MAPS_API_KEY}
      base-url: https://places.googleapis.com/v1
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

| ActivityType | Gọi Google Places? |
|---|---|
| `FOOD` | Có |
| `ATTRACTION` | Có |
| `ACCOMMODATION` | Có |
| `TRANSPORT` | Không |
| `OTHER` | Không |

- Nếu Google Places trả về kết quả → lưu `latitude`, `longitude`, `placeId`, `formattedAddress`, `geocodingProvider = "google"`
- Nếu không tìm thấy hoặc lỗi → `latitude/longitude = null`, không throw exception (graceful fallback)
- Query gửi đi: `"<searchQuery>, <destination>, Việt Nam"` (append destination nếu chưa có trong query)

---

## Retry & Error handling

### AI (OpenAI)
| Trường hợp | Hành vi |
|---|---|
| 400 Bad Request | Không retry → throw `AppException.badRequest` |
| 401 Unauthorized | Không retry → throw `AppException.badRequest` (API key sai) |
| 429 / 5xx | Retry tối đa `max-retries` lần |
| Timeout (60s) | Retry |
| JSON parse fail | Retry |
| Validate fail (days rỗng, thiếu searchQuery...) | Retry |
| Hết retry | Throw `AppException.internal` |

### Geocoding (Google Places)
| Trường hợp | Hành vi |
|---|---|
| Không tìm thấy địa điểm | Trả `null`, lat/lng = null |
| Timeout (5s) | Bắt exception, log warn, trả `null` |
| Lỗi HTTP bất kỳ | Bắt exception, log warn, trả `null` |

---

## AiUsage logging

Sau mỗi lần generate thành công, lưu vào bảng `ai_usages`:

| Field | Giá trị |
|---|---|
| `provider` | `"openai"` |
| `tokensIn` | `usage.prompt_tokens` từ response |
| `tokensOut` | `usage.completion_tokens` từ response |
| `costUsd` | `tokensIn/1M * 0.15 + tokensOut/1M * 0.60` |
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
