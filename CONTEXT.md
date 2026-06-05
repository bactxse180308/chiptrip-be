# CONTEXT.md — Dự án ChipTrip

> File này là **ngữ cảnh chuẩn** cho mọi AI agent (Claude, GPT, Gemini, Cursor, Copilot, v.v.) khi làm việc trên dự án này.
> Đọc hết file trước khi sinh code, đề xuất kiến trúc hoặc trả lời câu hỏi về dự án.

---

## 1. Đây là dự án gì?

**ChipTrip** là một ứng dụng **AI Travel Planner** dành cho người dùng Việt Nam — nhập điểm đến, ngày đi, ngân sách và gu du lịch, AI sẽ sinh ra lịch trình chi tiết theo từng khung giờ kèm dự toán chi phí, gợi ý chỗ ở/ăn uống/hoạt động, checklist chuẩn bị đồ.

- **Tên sản phẩm:** ChipTrip (mascot: 🐥 "Chip Trip")
- **Domain hiện tại (demo):** `https://chip-trip-ai-planner.lovable.app` — bản dựng nhanh trên Lovable để thử concept.
- **Ngôn ngữ giao diện chính:** Tiếng Việt.
- **Thị trường mục tiêu:** Người trẻ Việt Nam (Gen Z, sinh viên, dân văn phòng), giá vé/chi phí tính bằng **VNĐ**.

### Đề xuất giá trị (Value proposition)
- "30 giây có lịch trình hoàn hảo, không cần Google, không cần hỏi ai."
- Tiết kiệm thời gian research; biết trước chi phí; lịch trình tối ưu theo gu cá nhân.

### Không phải là gì (Non-goals — ít nhất ở MVP)
- **Không** là OTA (đặt phòng/vé trực tiếp — chỉ deep-link sang dịch vụ đặt chỗ bên thứ ba).
- **Không** hỗ trợ chuyến đi nước ngoài ở MVP (chỉ Việt Nam).
- **Không** tự train LLM model (dùng API có sẵn).

---

## 2. Trạng thái dự án

- **Thứ tự triển khai:** Web trước → Mobile App sau (App tái sử dụng backend của Web).

---

## 3. Tech stack đã chốt

### Web — Frontend
- **React 18+ với TypeScript 5+** (bắt buộc TS, không dùng JS thuần)
- **Vite** (build tool)
- **React Router** (routing)
- **TanStack Query (React Query)** cho fetch/cache
- **Axios** với interceptor gắn JWT
- **Zustand** (hoặc Context API cho state nhỏ) — không dùng Redux ở MVP
- **React Hook Form** + Zod cho validate form
- **Tailwind CSS** (theming, dark mode)
- **ESLint + Prettier**

### Web — Backend
- **Java 25+ với Spring Boot 4.x**
- **Spring Web** (REST)
- **Spring Security** + **JWT** (auth)
- **Spring Data JPA**
- **msSQL** (DB chính)

- **MapStruct** (Entity ↔ DTO mapper)
- **Bean Validation** (Jakarta Validation)
- **WebClient** (gọi LLM API & các API ngoài)
- **JUnit 5 + Mockito + Testcontainers** (test)
- **SpringDoc OpenAPI** (sinh Swagger từ code)

### Mobile App
- **Flutter 3+ với Dart**
- **Riverpod** (hoặc Bloc nếu team đã quen) cho state management
- **Dio** cho HTTP
- **json_serializable** cho parse JSON
- **go_router** cho routing
- **flutter_secure_storage** cho token

### AI
- **Đã chốt Gemini `gemini-2.5-flash`** qua API `https://generativelanguage.googleapis.com/v1beta` (`:generateContent`).
- **Yêu cầu bắt buộc:** dùng **JSON mode / Structured Output** (`responseMimeType: application/json` + `responseSchema`) để output luôn parseable.
- **Không** tự train model. **Không** self-host model open-source ở MVP.

### API ngoài
- **Goong REST API** (geocoding, autocomplete) + **Goong Map JS** (hiển thị bản đồ FE) + **SerpApi** (enrichment: rating, giờ mở cửa, ảnh)
- **OpenWeather API** (dự báo thời tiết)

### Infra & DevOps
- **Vercel** hoặc **Netlify** (deploy FE web)
- **Railway** hoặc **Render** (deploy BE)
- **Neon** hoặc **Supabase** (PostgreSQL cloud)
- **GitHub** + **GitHub Actions** (CI/CD)
- **Sentry** (error tracking)
- **Google Analytics** (số liệu người dùng)
- **Google Play Internal Testing / TestFlight** (phát hành app)

---

## 4. Kiến trúc tổng thể

```
                ┌──────────────────┐         ┌──────────────────┐
                │  Web (React+TS)  │         │ Mobile (Flutter) │
                └──────┬───────────┘         └──────────┬───────┘
                       │  HTTPS                         │
                       │  (JWT trong Authorization)     │
                       ▼                                ▼
                ┌───────────────────────────────────────────┐
                │      Backend — Spring Boot (REST API)     │
                │  Controller → Service → Repository        │
                │  Auth · Trip · AI · Maps · Weather        │
                └───────┬───────────────────┬───────────────┘
                        │                   │
                        ▼                   ▼
                ┌────────────┐      ┌─────────────────┐
                │ PostgreSQL │      │ External APIs   │
                │ (Flyway    │      │ - LLM (Gemini)  │
                │  migration)│      │ - Goong + SerpApi│
                └────────────┘      │ - OpenWeather   │
                                    └─────────────────┘
```

**Nguyên tắc:** Web và App **dùng chung 1 backend duy nhất**. API contract (OpenAPI) là "hợp đồng" — chốt sớm để 3 nhánh BE/FE/App có thể chạy song song.

---

## 5. Domain Model (lược đồ chính)

```
Role (1) ──< (n) User (1) ──< (n) Trip (1) ──< (n) Day (1) ──< (n) Activity
                   │                │
                   │                └──< (n) ChecklistItem
                   └──< (n) AiUsage
```

### Bảng & trường chính

**Role**
- `id` (Long, PK)
- `name` (string, unique) — vd `ROLE_USER`, `ROLE_ADMIN`

**User**
- `id` (Long, PK)
- `email` (unique, indexed)
- `passwordHash` (BCrypt)
- `fullName`
- `avatarUrl` (nullable)
- `aiCredits` (int, default 3) — số lượt AI còn lại
- `isActive` (bool, default true) — khóa tài khoản khi `false`; `UserPrincipal.isEnabled()` đọc trường này
- `emailVerified` (bool, default false)
- `role` (`@ManyToOne Role`) — **một user có đúng một role** (không dùng many-to-many)
- `lastLoginAt` (nullable)
- `createdAt`, `updatedAt`

> **RBAC:** JWT claim `role` lưu tên role dạng string (vd `"ROLE_ADMIN"`). `@PreAuthorize("hasRole('ADMIN')")` đặt ở **class level** trên mọi admin controller; `SecurityConfig` cũng bảo vệ `/api/v1/admin/**` với `.hasRole("ADMIN")`. Admin mặc định được seed qua `DataInitializer` (đọc `ADMIN_EMAIL` / `ADMIN_PASSWORD` từ env, bỏ qua nếu biến rỗng).

**Trip**
- `id` (UUID, PK)
- `userId` (FK User)
- `title` (string) — AI sinh, vd "Hành trình Đà Nẵng 3 ngày"
- `departure`, `destination` (string)
- `dateStart`, `dateEnd` (date)
- `peopleCount` (int)
- `budget` (long, VNĐ)
- `styles` (json/array) — gu du lịch đã chọn: ["food_tour", "couple", ...]
- `totalCost` (long, computed) — **DERIVED, tính từ Activity.cost**, không lưu cứng
- `createdAt`, `updatedAt`

**Day**
- `id` (UUID, PK)
- `tripId` (FK Trip)
- `dayNumber` (int, 1..N)
- `date` (date)
- `dayCost` (long, computed) — SUM Activity.cost trong ngày

**Activity**
- `id` (UUID, PK)
- `dayId` (FK Day)
- `time` (LocalTime) — vd 07:00
- `name` (string)
- `description` (text)
- `type` (enum: TRANSPORT, ACCOMMODATION, FOOD, ATTRACTION, OTHER)
- `cost` (long, VNĐ) — 0 nếu miễn phí
- `lat`, `lng` (double)
- `imageUrl` (string, nullable)
- `bookingUrl` (string, nullable)
- `displayOrder` (int) — cho phép sắp xếp lại

**ChecklistItem**
- `id` (UUID, PK)
- `tripId` (FK Trip)
- `category` (enum: PAPERS, CLOTHES, HYGIENE, OTHER)
- `name` (string) — vd "CMND/CCCD"
- `isChecked` (bool, default false)

**AiUsage** (log, để tính chi phí và rate-limit)
- `id`, `userId`, `tripId`
- `provider` (vd "openai", "gemini")
- `tokensIn`, `tokensOut` (int)
- `costUsd` (decimal)
- `createdAt`

---

## 6. API contract (chính)

Tất cả endpoint dưới `/api/v1/`. Auth qua header `Authorization: Bearer <JWT>` (trừ `/auth/*`).

| Method | Endpoint | Mục đích |
|---|---|---|
| POST | `/auth/register` | Đăng ký |
| POST | `/auth/login` | Đăng nhập, trả JWT + refresh token |
| POST | `/auth/refresh` | Đổi refresh token lấy access token mới |
| GET  | `/users/me` | Hồ sơ + số lượt AI còn lại |
| POST | `/trips/generate` | **Sinh lịch trình bằng AI** (trừ 1 lượt AI) |
| GET  | `/trips` | Danh sách chuyến của user (có phân trang) |
| GET  | `/trips/{id}` | Chi tiết 1 chuyến (gồm days + activities) |
| PATCH| `/trips/{id}/activities/{aid}` | Sửa 1 activity |
| DELETE | `/trips/{id}` | Xoá chuyến |
| GET  | `/weather?city=...&from=...&to=...` | Thời tiết theo ngày của chuyến |
| GET  | `/places/search?q=...` | Autocomplete thành phố (Maps) |

**Admin endpoints** (yêu cầu `ROLE_ADMIN`, tất cả dưới `/api/v1/admin/`):

| Method | Endpoint | Mục đích |
|---|---|---|
| GET    | `/admin/stats/dashboard` | Tổng số user, trip, AI call & chi phí tháng này |
| GET    | `/admin/stats/users?from=&to=` | Số user đăng ký theo ngày |
| GET    | `/admin/stats/trips?from=&to=` | Số trip tạo theo ngày |
| GET    | `/admin/stats/ai-cost?from=&to=` | Chi phí AI theo provider/tháng |
| GET    | `/admin/users` | Danh sách user (filter: search, isActive, role) |
| GET    | `/admin/users/{id}` | Chi tiết user + trips + AI usage |
| PATCH  | `/admin/users/{id}` | Sửa fullName / aiCredits |
| PATCH  | `/admin/users/{id}/status` | Bật/tắt tài khoản (`{ "enabled": true/false }`) |
| POST   | `/admin/users/{id}/grant-credits` | Cộng AI credits (`{ "amount": N }`) |
| POST   | `/admin/users/{id}/roles` | Đặt role cho user (`{ "role": "ROLE_ADMIN" }`) |
| GET    | `/admin/trips` | Danh sách tất cả trip (filter: userId, from, to) |
| GET    | `/admin/trips/{id}` | Chi tiết trip |
| DELETE | `/admin/trips/{id}` | Xóa trip |
| GET    | `/admin/ai-usages` | Log AI usage (filter: userId, provider, from, to) |
| GET    | `/admin/ai-usages/summary` | Tổng hợp chi phí AI theo provider/tháng |

**Quy ước response:**
```json
{
  "data": { ... },          // nội dung chính
  "error": null              // hoặc { "code": "...", "message": "..." }
}
```

**Quy ước lỗi:** dùng HTTP status đúng nghĩa (400 validation, 401 auth, 403 forbidden, 404 not found, 429 rate-limit, 500 server). Body lỗi chuẩn:
```json
{ "data": null, "error": { "code": "AI_NO_CREDITS", "message": "Hết lượt AI" } }
```

---

## 7. Tích hợp AI — quy ước quan trọng

### 7.1. Luồng sinh lịch trình
```
Client → POST /trips/generate (input)
     → Backend: TripService
        → Trừ aiCredits (transactional)
        → AiService.generateItinerary(input)
            → Gọi LLM API (JSON mode/Structured Output)
            → Validate JSON theo schema
            → Retry tối đa 2 lần nếu JSON sai
        → Map JSON → Trip + Day + Activity entities (Mapper)
        → Persist
        → Log AiUsage
     → Trả Trip đầy đủ
```

### 7.2. Yêu cầu prompt
- **System prompt:** đóng vai chuyên gia du lịch Việt Nam, output **chỉ JSON**, không markdown, không prose.
- **User prompt:** chứa input cấu trúc + schema JSON kỳ vọng.
- **Bắt buộc** dùng JSON mode / response_format json_schema để API tự enforce.
- Giá trị `cost` luôn là **integer VNĐ**, không có dấu phẩy/đơn vị.
- Tên địa điểm phải có thật ở Việt Nam, không bịa.

### 7.3. Schema JSON kỳ vọng
```json
{
  "title": "string",
  "days": [
    {
      "dayNumber": 1,
      "date": "YYYY-MM-DD",
      "activities": [
        {
          "time": "HH:mm",
          "name": "string",
          "description": "string",
          "type": "TRANSPORT|ACCOMMODATION|FOOD|ATTRACTION|OTHER",
          "cost": 350000,
          "lat": 16.0544,
          "lng": 108.2022,
          "bookingUrl": "string|null"
        }
      ]
    }
  ],
  "checklist": [
    { "category": "PAPERS|CLOTHES|HYGIENE|OTHER", "name": "string" }
  ]
}
```

### 7.4. Bảo mật API key
- **API key LLM/Maps/Weather đặt trong biến môi trường** (Spring đọc từ `${GEMINI_API_KEY}`, `${GOONG_API_KEY}`, `${SERPAPI_API_KEY}`, `${OPENWEATHER_API_KEY}`).
- **Tuyệt đối không hardcode, không commit lên Git.**
- Frontend **không** gọi LLM trực tiếp — chỉ gọi backend.

---

## 8. Quy ước code (Code conventions)

### Backend (Spring Boot)
- Cấu trúc package theo **feature**, không theo **layer**:
  ```
  com.chiptrip
   ├── auth/        (Controller, Service, Repository, DTO của Auth)
   ├── trip/        (...)
   ├── ai/
   ├── user/
   ├── common/      (Exception handler, BaseEntity, Config)
   └── ChipTripApplication.java
  ```
- **Layer:** Controller → Service → Repository.
- **Không trả Entity trực tiếp ra API** — luôn map qua DTO bằng MapStruct.
- `@RestControllerAdvice` xử lý exception toàn cục, trả format error chuẩn.
- DTO request có `@Valid` + Bean Validation (`@NotBlank`, `@Email`, `@Min`...).
- Service annotate `@Transactional` khi có ghi DB nhiều bảng.
- **Không bắt `Exception` chung chung** — bắt cụ thể.
- Test: tên `*Test.java` cho unit, `*IT.java` cho integration (Testcontainers).

### Frontend Web (React + TS)
- Cấu trúc theo **feature folder**:
  ```
  src/
   ├── features/
   │   ├── auth/      (components, hooks, api, types)
   │   ├── planning/
   │   ├── trip/
   │   └── profile/
   ├── shared/        (Button, Input, Modal, ...)
   ├── lib/           (axios, queryClient, utils)
   ├── routes/        (router config)
   ├── types/
   └── App.tsx
  ```
- Hook custom **bắt đầu bằng `use`**: `useAuth`, `useTrip`.
- **Component file:** PascalCase, function component, named export.
- **State derived** (vd tổng tiền) phải tính bằng `useMemo` từ nguồn duy nhất — **không lưu redundant state**.
- Gọi API qua TanStack Query, không gọi axios trực tiếp trong component.
- **Type/interface** dùng PascalCase, không prefix `I` (theo style hiện đại).
- Mọi prop có TypeScript type.

### Mobile App (Flutter)
- Cấu trúc theo feature:
  ```
  lib/
   ├── features/
   │   ├── auth/      (screens, widgets, providers, repo)
   │   ├── planning/
   │   └── ...
   ├── core/          (theme, router, api_client, storage)
   ├── shared_widgets/
   └── main.dart
  ```
- Class PascalCase, biến/method lowerCamelCase.
- Model parse JSON bằng `json_serializable`.
- Provider/Bloc tách riêng khỏi widget.

### Git
- Branch: `main` (production) · `dev` (staging) · `feature/<ten-tinh-nang>` · `fix/<ten-bug>`.
- Commit theo Conventional Commits: `feat: ...`, `fix: ...`, `refactor: ...`, `test: ...`.
- PR phải có description rõ, link issue, ít nhất 1 reviewer.

---

## 9. Bug đã biết từ bản demo — KHÔNG ĐƯỢC LẶP LẠI

Đây là các bug phát hiện khi review bản demo Lovable. Bản chính thức phải fix dứt điểm:

| # | Bug | Cách xử lý chuẩn |
|---|---|---|
| 1 | Tổng tiền header (4.8M) lệch với sidebar (3.2M) | **Single source of truth**: `totalCost` là derived state, tính bằng `useMemo` từ mảng activities. Mọi nơi cùng đọc 1 biến. |
| 2 | Thumbnail activity bị vỡ, hiện alt text | `<img>` có `onError` fallback về ảnh placeholder. Nếu LLM không trả `imageUrl`, dùng ảnh mặc định theo `type`. |
| 3 | Widget thời tiết hiển thị tuần hiện tại thay vì ngày chuyến đi | Endpoint `/weather` nhận `from` và `to` của chuyến; FE map đúng ngày, ngày ngoài tầm dự báo hiển thị "—" thay vì giấu. |
| 4 | Date picker cho phép chọn ngày quá khứ làm ngày khởi hành | `min={today}` cho HTML `<input type="date">` và validate phía BE: `dateStart >= today` và `dateEnd > dateStart`. |
| 5 | Tài khoản có 0 lượt AI vẫn generate được | Backend kiểm tra `aiCredits > 0` trong **transaction**, trừ trước khi gọi LLM, rollback nếu LLM lỗi nặng. |
| 6 | Preset ngân sách hở khoảng (2-3M và 5-8M không thuộc preset nào) | Thiết kế preset liền mạch, vd `<3M / 3-7M / 7M+`, hoặc cho input số tự do. |
| 7 | Title lịch trình không khớp input người dùng | Prompt yêu cầu rõ "title phải nhắc tới điểm đến và ngân sách user chọn"; validate sau khi LLM trả về. |

---

## 10. Quality bars (mức chất lượng tối thiểu)

- **Testing**
  - Backend: service layer coverage ≥ 60%; có integration test cho `POST /trips/generate` (mock LLM) và auth flow.
  - Frontend: test các util/derived function; ít nhất 1 E2E test (Cypress/Playwright) cho luồng tạo lịch trình.
- **Performance**
  - Response API (trừ generate AI): < 500ms p95.
  - Generate AI: hiển thị skeleton/progress, timeout 60s phía client.
  - Web: First Contentful Paint < 2.5s trên mạng 4G.
- **Security**
  - Hash password bằng BCrypt (cost ≥ 10).
  - JWT access token 15 phút, refresh token 7 ngày, lưu refresh token an toàn (httpOnly cookie hoặc secure storage).
  - CORS chỉ cho phép domain FE chính thức.
  - Tất cả input validate phía BE (đừng tin FE).
  - Không log token, password, API key.
  - Rate limit `POST /trips/generate`: tối đa 5 request/phút/user.
- **Accessibility (FE)**
  - Tất cả input có `<label>`; nút có `aria-label` khi cần.
  - Contrast text/background đạt WCAG AA.

---



## 13. Cách AI agent nên làm việc trên dự án này

Khi một AI agent được hỏi/sai làm việc trên dự án này:

### NÊN
- ✅ Đọc hết CONTEXT.md trước
- ✅ Dùng đúng stack đã chốt (không gợi ý đổi sang Next.js, Django, FastAPI... trừ khi được hỏi đánh giá).
- ✅ Sinh code theo convention ở mục 8 (feature folder, DTO + Mapper, derived state, v.v.).
- ✅ Khi viết prompt cho LLM: luôn dùng JSON mode/Structured Output, validate JSON, retry khi sai.
- ✅ Trả lời bằng **tiếng Việt**, giữ nguyên thuật ngữ tiếng Anh chuyên ngành.
- ✅ Khi đụng tới UI/luồng đã có ở demo, kiểm tra mục 9 (bug đã biết) để không lặp lại.
- ✅ Khi tư vấn về kiến trúc, ưu tiên đơn giản — đây là MVP, không cần microservice, Kafka, K8s, v.v.

### KHÔNG NÊN
- ❌ Hardcode API key trong code/commit.
- ❌ Trả Entity JPA trực tiếp ra API (phải qua DTO).
- ❌ Đặt logic tính tổng tiền ở nhiều chỗ (vi phạm single source of truth).
- ❌ Đề xuất chuyển sang stack khác mà không hỏi.
- ❌ Sinh code TypeScript thiếu type (tránh `any` trừ trường hợp bất khả kháng).
- ❌ Viết code cho phép user chọn ngày quá khứ làm ngày khởi hành.
- ❌ Bỏ qua xác thực JWT ở endpoint không phải `/auth/*`.

### Khi không chắc
Hỏi lại người dùng (sinh viên dev này là người ra quyết định cuối). Đừng tự ý mở rộng scope ngoài MVP.

---

## 14. Tham khảo nhanh

- **Demo UI:** `https://chip-trip-ai-planner.lovable.app`
- **Tham khảo công nghệ:**
  - Spring Boot: https://spring.io/projects/spring-boot
  - React Query: https://tanstack.com/query
  - Flutter: https://flutter.dev
  - OpenAI Structured Outputs: https://platform.openai.com/docs/guides/structured-outputs
  - Gemini JSON Mode: https://ai.google.dev/gemini-api/docs/structured-output

---

*File này là "single source of truth" về dự án. Khi có thay đổi quan trọng (đổi stack, thêm tính năng vào MVP, sửa API contract), cập nhật vào đây trước khi bắt tay code.*
