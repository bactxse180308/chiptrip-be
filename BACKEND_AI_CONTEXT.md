# ChipTrip Backend — AI Fast Context

> Mục tiêu: file đọc-first cho AI/agent khi làm việc với `chiptrip-be`, để tránh phải quét toàn bộ codebase ở mỗi task.
>
> Snapshot được tổng hợp từ code `main` hiện tại. **Nếu nội dung file này khác code, code là source of truth.** Khi thay đổi kiến trúc, domain model, endpoint, credit/premium, provider hoặc background job, hãy cập nhật file này trong cùng PR/commit.

## 0. Cách dùng file này

1. Đọc file này trước.
2. Xác định module + critical files ở mục **Where to read next**.
3. Chỉ mở implementation liên quan trực tiếp đến task.
4. Trước khi sửa luồng trip generation, credit, place enrichment, payment hoặc auth, đọc mục **DO NOT BREAK**.
5. Tuân thủ `AGENTS.md`: thay đổi tối thiểu, không refactor lan sang phần không liên quan, verify trước/sau.

---

## 1. Product và architecture tổng quan

ChipTrip là backend cho ứng dụng AI travel planner. Backend là **modular monolith Spring Boot**, chia package theo domain dưới:

`com.tranbac.chiptripbe.module.*`

Các module chính:

| Module | Trách nhiệm chính |
|---|---|
| `auth` | Login/register, refresh token, Google OAuth, verify email, forgot/reset password, OTP |
| `user` | User/role, profile, password, entitlement, paid/trial AI credits, admin user/role |
| `trip` | Trip, day, activity, checklist, member/invite, expense, social, PDF export, AI generation orchestration, activity replacement |
| `ai` | Gemini/OpenAI-compatible calls, key rotation, itinerary validation, destination suggestions, AI usage/cost audit |
| `place` | Place cache, enrichment, photo refresh, reviews, place query |
| `geocoding` | Goong + SerpApi + optional Google Maps scraper clients/key pools/rate limits |
| `external` | Route/distance/weather/place lookup facade APIs |
| `flight` | Flight suggestion/cache |
| `payment` | SePay order + webhook + credit grant |
| `notification` | REST notifications, WebSocket/STOMP, domain events, reminder/weather/post-trip schedulers |
| `chat` | Support chat, admin chat, R2 image upload/storage |
| `moderation` | User reports + admin moderation |
| `stats` | Admin dashboard/analytics/revenue/AI cost |

Cross-cutting code nằm trong `common/`: `config`, `entity`, `enums`, `exception`, `filter`, `resilience`, `response`, `security`, `service`, `util`.

Kiến trúc thông thường:

`Controller -> Service interface -> ServiceImpl -> Repository -> Entity`

DTO request/response nằm trong module tương ứng. Không expose JPA entity trực tiếp qua controller nếu pattern hiện tại đã dùng DTO.

---

## 2. Tech stack hiện tại

- Java 21
- Spring Boot 4.0.6
- Spring MVC + WebFlux/WebClient
- Spring Security, stateless JWT
- Spring Data JPA / Hibernate
- Microsoft SQL Server
- Spring Validation
- Spring Mail
- WebSocket/STOMP
- Bucket4j rate limiting
- Resilience4j circuit breaker
- MapStruct + Lombok
- JJWT 0.12.6
- OpenAPI / springdoc
- Cloudflare R2 qua AWS S3 SDK v2
- OpenHTMLToPDF + SVG support cho trip PDF
- Testcontainers MSSQL + Spring Security test

API response envelope chuẩn:

```json
{
  "success": true,
  "message": null,
  "data": {},
  "meta": null,
  "timestamp": "..."
}
```

`common.response.ApiResponse` là wrapper chính; pagination dùng `PageMeta`.

---

## 3. Configuration / integrations

Config chính: `src/main/resources/application.yml`; có `optional:file:.env[.properties]`.

### Database

- Env: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- SQL Server dialect
- `spring.jpa.hibernate.ddl-auto=update`
- `open-in-view=false`

Dù Hibernate auto-update đang bật, repo vẫn có migrations thủ công trong `migrations/`. Khi thay đổi schema quan trọng/production-sensitive, kiểm tra migration hiện hữu trước khi chỉ dựa vào ddl-auto.

### JWT

- `JWT_SECRET`
- Access token: 15 phút
- Refresh token: 7 ngày

### AI

- `AI_API_KEYS` (comma-separated), fallback `AI_API_KEY`
- `AI_BASE_URL`
- Model hiện tại: `gemini-2.5-flash`
- Timeout: 300s
- Max retries: 1
- AI key pool có rotation khi provider key lỗi/quota.
- Cost log theo pricing config: input `0.30 USD/1M`, output `2.50 USD/1M`.

### Maps / places

- Goong: `GOONG_API_KEY`, base `https://rsapi.goong.io`, timeout 5s, rate limit config.
- SerpApi: `SERPAPI_API_KEY` hỗ trợ nhiều key; cache/retry/quota cooldown/rate limit.
- Optional scraper sidecar: `SCRAPER_ENABLED`, `SCRAPER_URL`, mặc định `http://127.0.0.1:8899`.
- Provider resilience/circuit breaker được config riêng cho Goong, SerpApi, LLM.

### Other

- OpenWeather: `OPENWEATHER_API_KEY`
- PostHog: `POSTHOG_PROJECT_ID`, `POSTHOG_PERSONAL_KEY`, host configurable.
- R2: endpoint/access/secret/bucket/public URL.
- SMTP/Google OAuth dùng env tương ứng.
- SePay: API key, bank info, QR config, order prefix, plan pricing.

### CORS

Allowed origins hiện gồm localhost `3000`, `5173`, `chiptrip.online`, `www.chiptrip.online`.

---

## 4. Security model

`common/config/SecurityConfig.java`:

- CSRF disabled; JWT stateless.
- BCrypt strength 12.
- Method security enabled.
- `/api/v1/admin/**` yêu cầu role `ADMIN`.
- `/api/v1/payments/**` authenticated.
- Auth endpoints, shared trip, Swagger, WebSocket handshake, webhook là public theo matcher.
- Social/review GET nhất định là public; POST/DELETE cùng path vẫn cần JWT.
- WebSocket handshake `/ws/**` public nhưng STOMP CONNECT được authenticate bởi `notification/config/JwtChannelInterceptor`.

**Role và Premium là hai khái niệm khác nhau:**

- Role dùng RBAC (`USER`, `ADMIN`).
- Premium **không phải role**.
- Premium = trạng thái suy ra từ paid AI credit balance còn > 0.

Không thêm `ROLE_PREMIUM` để gate feature.

---

## 5. User, credits và entitlement

Entity chính: `module/user/entity/User.java`.

Các field quan trọng:

- `email`, `passwordHash`, `fullName`, avatar
- role
- OAuth provider/id
- preferences dùng để cá nhân hóa AI itinerary
- `aiCredits`: whole-credit compatibility field
- `aiCreditUnits`: paid balance chính; **100 units = 1.00 credit**
- `trialCreditBalance`: free trial hôm nay, 0/1, không cộng dồn
- `trialCreditDate`: ngày cấp trial theo `Asia/Ho_Chi_Minh`

`User.isPremium()` trả true khi `effectiveAiCreditUnits() > 0`.

### Credit rules quan trọng

- User mới có paid credit = 0.
- Generate trip dùng paid/trial theo policy trong `CreditService`.
- Validation chỉ check khả năng generate, **không trừ credit**.
- Credit generate chỉ được consume atomically trong transaction persist trip.
- Activity replacement có đơn vị charge riêng; implementation hiện dùng `25 units = 0.25 credit` khi cần charge.
- `EntitlementPolicy/EntitlementService` là nơi đọc/gate giới hạn feature; không hardcode quyền FE-only.

---

## 6. Core trip domain

### Trip graph

`Trip`

- owner `User`
- title, departure, destination
- dateStart/dateEnd
- peopleCount, budgetVnd, styles JSON string
- shareToken
- inviteToken
- public/social fields: `isPublic`, `publishedAt`, denormalized `likesCount`, `commentsCount`
- snapshot premium lúc tạo: `generatedAsPremium`
- activity swap quota snapshot: `activitySwapFreeLimit`, `activitySwapFreeUsed`
- durable enrichment state: `enrichmentPending`, `enrichmentAttempts`, `enrichmentNextAttemptAt`
- children: `TripDay`, `ChecklistItem`, `TripMember`

`TripDay -> Activity[]`

Activity lưu cả nội dung itinerary và dữ liệu map/place như lat/lng, search query, place id/cache id, address, provider, image/booking URL.

Các entity phụ:

- `TripMember`: collaboration/member role/state
- `TripExpense`: expense
- `TripLike`, `TripComment`: social
- `ActivityAlternativeSession`: phiên gợi ý thay activity, có TTL/status/options

### Ownership/access

Luồng private trip thường query theo `userId` hoặc membership/service check. Không thay repo query thành `findById()` rồi bỏ ownership/membership guard.

---

## 7. Trip generation — luồng quan trọng nhất

### API

Base: `/api/v1/trips`

- `POST /generate`: sync
- `POST /generate-async`: async, optional `X-Generation-Id`
- `GET /generation-status`
- `GET /`: list own trips
- `GET /{id}`: detail
- `PATCH /{id}`
- `DELETE /{id}`
- `POST /{id}/clone`
- `POST /{id}/share`
- `DELETE /{id}/share`
- `GET /shared/{shareToken}` public
- `GET /{id}/export/pdf`

### Sync generation flow

`TripController -> TripGenerationLock -> TripServiceImpl.generateTrip`

`TripServiceImpl.generateTrip` cố ý chạy với `Propagation.NOT_SUPPORTED` để **không giữ DB transaction trong lúc gọi HTTP/provider lâu**.

Flow:

1. Validate user/request/entitlement/credits/dates.
2. Call AI để generate itinerary.
3. AI output được validate trước persist.
4. Resolve/geocode places.
5. Persist trip graph + AiUsage + atomic credit consumption trong transaction ngắn của `TripGenerationPersistenceServiceImpl`.
6. Sau commit, chạy full background enrichment cho các place còn thiếu.

### Async generation flow

`POST /generate-async`:

1. validate fail-fast
2. acquire per-user `TripGenerationLock`
3. mark `TripGenerationStatusTracker` running
4. submit `AsyncTripGenerator`
5. kết quả được push qua WebSocket user queue `/user/queue/trip-generation`
6. lock/status phải được cleanup đúng cả success/error paths

`TripGenerationLock` ngăn một user tạo nhiều job sync/async cùng lúc để tránh trip trùng, provider calls trùng và credit race.

### Credit transaction invariant

Trong `TripGenerationPersistenceServiceImpl.persistGeneratedTrip`:

- save trip/day/activity/checklist
- log `AiUsage`
- `CreditService.consumeForGenerate(userId)` atomic, paid-before-trial policy
- nếu consume fail -> transaction rollback toàn bộ trip + AiUsage, user không mất lượt
- low-paid-credit có thể publish `AiCreditsLowEvent`

**KHÔNG chuyển credit deduction lên validate/controller và không trừ trước provider call.**

### Premium snapshot

Khi persist trip:

- `generatedAsPremium = user.isPremium()` tại thời điểm tạo
- premium trip được snapshot free activity-swap limit hiện tại (implementation: 4)

Một số feature replacement cho phép trip đã được tạo khi Premium tiếp tục dùng free swap đã snapshot kể cả khi paid balance hiện tại về 0.

---

## 8. Place resolution và enrichment

Đây là subsystem phức tạp thứ hai sau trip generation.

Critical files:

- `place/service/impl/PlaceEnrichmentServiceImpl.java`
- `place/service/impl/PlaceCacheServiceImpl.java`
- `place/entity/PlaceCache.java`
- `geocoding/client/GoongClient.java`
- `geocoding/client/SerpApiClient.java`
- `geocoding/client/MapsScraperClient.java`
- `trip/service/impl/TripServiceImpl.java`
- `trip/scheduler/TripEnrichmentScheduler.java`

### Strategy khi generate

- Collect geocodable activities (FOOD, ATTRACTION, ACCOMMODATION, TRANSPORT) có `searchQuery`.
- Day 1: enrich FULL đồng bộ để response đầu tiên có dữ liệu tốt.
- Day 2..N: resolve BASIC/toạ độ nhanh rồi full enrich background.
- Resolve/enrich là fail-soft theo từng activity; một place lỗi không nên làm fail toàn bộ trip nếu code hiện tại đang degrade gracefully.
- Place data được cache để giảm provider calls.

### Durable background enrichment

Trip persist `enrichmentPending=true` nếu còn work.

Background worker cố claim trip bằng DB lease. Nếu executor drop job/app restart/provider cooldown/error:

- cờ pending vẫn giữ
- `TripEnrichmentScheduler` sweep lại
- retry dùng exponential backoff từ khoảng 5 -> 10 -> 20 -> 40 -> 60 phút
- DB claim/lease ngăn worker và sweeper enrich cùng trip đồng thời

Khi hoàn tất mới clear pending và có thể push update qua `/queue/trip-enrichment`.

**Không biến state này thành chỉ in-memory.** `enrichmentPending` tồn tại chính để phục hồi sau restart/queue discard.

### Live place data

Với activity thuộc các ngày background, image không nhất thiết đóng băng ngay vào Activity. Trip detail có logic fallback đọc `PlaceCache` live để ảnh/review xuất hiện sau khi enrichment hoàn tất mà không phải rewrite activity.

---

## 9. AI module

Critical files:

- `ai/service/impl/AiGenerateServiceImpl.java`
- `ai/service/AiItineraryValidator.java`
- `ai/service/AiKeyPool.java`
- `ai/service/impl/AiSuggestServiceImpl.java`
- `ai/service/impl/AiUsageServiceImpl.java`

Responsibilities:

- Generate itinerary bằng OpenAI-compatible endpoint/model Gemini.
- Rotate API keys khi key quota/auth lỗi.
- Parse + validate structured itinerary trước persistence.
- Destination suggestion API.
- Record token usage/cost vào `AiUsage`.
- Admin/user usage endpoints và statistics.

Khi thay prompt/schema AI, kiểm tra đồng thời:

1. request DTO
2. prompt builder
3. response parsing
4. `AiItineraryValidator`
5. persistence mapping
6. FE contract / `TripGenerateResponse`

Không chỉ sửa prompt rồi giả định downstream vẫn đúng.

---

## 10. Activity alternatives / replacement

Critical file: `trip/service/impl/ActivityAlternativeServiceImpl.java`.

Flow:

1. Load trip/day/activity với ownership guards.
2. Gate feature theo `generatedAsPremium || currentPremium`.
3. Có thể reuse pending session active.
4. Call AI ngoài DB transaction dài (`NOT_SUPPORTED`).
5. Enrich/filter options.
6. Save `ActivityAlternativeSession`, TTL hiện tại 15 phút.
7. Khi apply, lock session/trip để chống double-use/race.
8. Consume free quota hoặc paid credit atomically.
9. Update target activity/activities và expire conflicting sessions.

Current charging constants trong implementation:

- paid replacement charge: 25 units = 0.25 credit
- generated premium trip free-swap snapshot hiện là 4

Accommodation/category rules có logic đặc biệt; đọc service trước khi thay behavior replacement.

---

## 11. Auth flow

Module `auth` có:

- email/password register/login
- refresh token entity/repository
- email verification token
- forgot/reset password token
- OTP send/verify/reset-with-OTP
- Google login

Critical file: `auth/service/impl/AuthServiceImpl.java`.

JWT principal: `common/security/UserPrincipal`; request auth qua `JwtAuthFilter`.

Các endpoint auth được permit public trong `SecurityConfig`; business checks vẫn phải nằm trong service.

---

## 12. Payment + Premium

Provider hiện tại: SePay.

Critical files:

- `payment/service/impl/OrderServiceImpl.java`
- `payment/service/impl/PaymentServiceImpl.java`
- `payment/controller/SepayWebhookController.java`
- `user/service/CreditService.java` + impl

Config plan hiện tại:

- `PREMIUM`: 49,000 VND -> 1 paid credit
- `PRO`: 109,000 VND -> 3 paid credits

Order hết hạn mặc định 15 phút.

Webhook rules:

- chỉ inbound transfer
- `sepayTransactionId` idempotency
- match order code từ transfer fields
- require pending order + amount đủ
- conditional atomic `PENDING -> PAID`
- chỉ winner được cộng credit
- premium tự xuất hiện vì paid credit > 0; **không đổi user role**
- transaction webhook luôn được audit khi phù hợp flow

Không bỏ idempotency/conditional update: webhook có thể retry hoặc đến đồng thời.

---

## 13. Collaboration / sharing / social

Trip có 3 cơ chế khác nhau, đừng trộn:

- `shareToken`: read-only/public shared trip link.
- `inviteToken`: mời user thành member/collaborator.
- `isPublic/publishedAt`: publish lên discovery/social feed.

Controllers/services liên quan:

- `TripInviteController`
- `TripMemberController`
- `TripSocialController`
- `TripMemberServiceImpl`
- `TripSocialServiceImpl`

Social có like/comment và denormalized counters trên `Trip`; khi thay mutations phải giữ counters sync với rows thật.

Notification events được phát cho likes/comments/member actions theo implementation.

---

## 14. Checklist, expense, PDF

- Checklist: `ChecklistController`, `ChecklistServiceImpl`
- Expenses: `TripExpenseController`, `TripExpenseServiceImpl`
- PDF: `TripExportServiceImpl`

PDF dùng OpenHTMLToPDF và bundled fonts/SVG resources. Nếu thay DTO/trip detail field mà PDF phụ thuộc, kiểm tra export template/mapping.

---

## 15. Notifications / WebSocket / schedulers

Module notification gồm:

- persistence `Notification`
- REST CRUD/read endpoints
- STOMP/WebSocket config
- event listener chuyển domain events thành notifications + realtime message
- scheduler:
  - trip reminder
  - weather alert
  - post-trip

Domain events hiện có cho:

- low AI credit
- trip liked/commented
- member added/joined
- trip reminder
- weather alert
- post-trip
- support chat user/admin messages

WebSocket auth không nằm ở HTTP handshake; `JwtChannelInterceptor` xử lý STOMP CONNECT.

Khi thêm realtime event, giữ cả persistence + WebSocket semantics nếu feature yêu cầu history và realtime.

---

## 16. Chat + R2

Support chat module có user/admin controllers, conversation/message entities và status/role/type enums.

Ảnh chat upload qua Cloudflare R2 (`R2StorageServiceImpl`).

Config hiện giới hạn ảnh chat khoảng 5 MB; Spring multipart request có limit riêng 6/8 MB.

Chat events tích hợp notification module cho new support message/support reply.

---

## 17. Moderation

`ContentReport` hỗ trợ report target/status; user submit report, admin list/resolve qua `AdminModerationController`.

Khi sửa social/review entity deletion/hiding, kiểm tra moderation target resolution để tránh report trỏ entity không còn tồn tại hoặc admin action mất hiệu lực.

---

## 18. Place reviews và photo refresh

Place module ngoài provider enrichment còn có user review subsystem:

- `PlaceReviewController`
- `PlaceReviewServiceImpl`
- `PlaceReview`

GET review/summary endpoints được public theo security config; mutations authenticated.

Photo sync có `PhotoSyncStatus`, failure report DTO và `PlacePhotoRefreshWorker/PlacePhotoServiceImpl`; migration `2026-07-21-place-photo-refresh.sql` liên quan.

---

## 19. External / route / weather / flight

`external` là facade cho place/weather/routes/distance matrix. `RoutesController` và `ExternalController` gọi `ExternalApiService`.

`flight` có `FlightCache` để tránh gọi provider/search lặp. Khi thay TTL/query key, đọc `FlightServiceImpl` + repository cùng lúc.

Provider calls nên đi qua client/service/resilience hiện hữu; không tạo WebClient/OkHttp ad-hoc ở controller.

---

## 20. Admin / stats

Admin surface gồm:

- users/roles
- trips
- AI usage
- chat
- moderation
- stats/dashboard

`stats` aggregate data từ repositories và analytics/PostHog integration. Admin endpoints nằm dưới `/api/v1/admin/**`, được SecurityConfig gate `ROLE_ADMIN`.

---

## 21. Database / migrations đáng chú ý

Manual migration files hiện có:

- `2026-06-11-social-and-reviews.sql`
- `2026-06-25-premium-trip-columns.sql`
- `2026-06-30-widen-goong-place-id.sql`
- `2026-07-08-place-cache-negative-cache.sql`
- `2026-07-08-trip-enrichment-durable.sql`
- `2026-07-13-enrichment-retry-schedule.sql`
- `2026-07-21-place-photo-refresh.sql`

Các migration này cho thấy những phần schema đặc biệt nhạy: social/reviews, premium snapshot, provider IDs, negative cache, durable enrichment/retry và photo refresh.

`common/config/EnumCheckConstraintFixer` và `PlaceCacheDeduplicator` cũng là startup/schema-maintenance code cần xem trước khi đổi enum/place uniqueness.

---

## 22. Concurrency / transaction boundaries

Các pattern phải giữ:

- HTTP/provider calls dài không nằm trong DB transaction dài nếu service hiện dùng `NOT_SUPPORTED`.
- Trip generation persistence là transaction ngắn, atomic cả trip graph + AiUsage + credit consume.
- Per-user generation lock ngăn duplicate jobs.
- Activity alternative apply dùng DB locking/session status để chống double apply/charge.
- Payment webhook dùng idempotency + conditional update.
- Background enrichment dùng durable DB state/claim/retry, không chỉ executor memory.
- `open-in-view=false`: service/mapping phải load data cần thiết trong transaction/repository query; đừng dựa vào lazy loading ở controller.

---

## 23. Provider resilience / caching

Provider subsystem có:

- rate limiting
- key pools
- circuit breaker
- Retry-After/cooldown
- positive cache
- negative cache
- background fallback scraper

Nếu provider trả 429/401/403/timeout/5xx, đừng tự thêm retry loop vô hạn. Đọc `common/resilience`, `SerpApiClient`, `GoongClient`, key pools và config trước.

Negative cache phải phân biệt “thật sự không tìm thấy” với transient provider failure; không cache 429/timeout như permanent not-found.

---

## 24. API family map

Các family chính theo controller hiện tại:

- `/api/v1/auth/**`
- `/api/v1/users/**` / user-profile endpoints trong `UserController`
- `/api/v1/entitlements/**`
- `/api/v1/trips/**`
- nested trip activity/checklist/member/invite/expense/social APIs
- `/api/v1/places/**`
- `/api/v1/ai/**` / AI suggestion & usage endpoints
- `/api/v1/payments/**`
- `/api/v1/webhooks/sepay`
- notification APIs + `/ws/**`
- chat APIs
- external/routes/weather/place APIs
- flight APIs
- moderation/report APIs
- `/api/v1/admin/**`

**Endpoint path/method cụ thể nên lấy từ controller tương ứng trước khi thay contract.** File này là map kiến trúc, không thay Swagger/controller annotations làm source of truth cho từng endpoint.

---

## 25. CI/CD

Workflow: `.github/workflows/ci-cd.yml`.

- PR vào `main`: build + test.
- Push `main`: build/test rồi copy jar lên VPS và restart service.
- JDK 21 / Maven wrapper.
- Health check VPS hiện chấp nhận HTTP 200 hoặc 401 từ actuator health endpoint.

**Hệ quả:** commit trực tiếp vào `main` có thể trigger deploy. Với thay đổi code/schema nhạy cảm, ưu tiên branch + PR trừ khi user yêu cầu trực tiếp khác.

---

## 26. Tests và docs hỗ trợ

Tests hiện tập trung dưới `src/test/java/...`; có Testcontainers MSSQL setup.

Các docs hữu ích khi cần đi sâu:

- `CONTEXT.md`: context lịch sử/canonical dài hơn
- `AI_INTEGRATION.md`: AI integration
- `API_DIVISION.md`: API ownership/division
- `chiptrip_erd_full.md`: ERD
- `CREDIT_PREMIUM_SPEC.md`: credit/premium business rules
- `prompt-geocoding-optimization.md`: prompt + geocoding design
- `docs/notification-feature.md`: notification subsystem
- `migrations/*.sql`: production schema evolution
- `AGENTS.md`: coding/agent rules

File này ưu tiên **fast onboarding**; khi task chạm business rule chuyên sâu, đọc doc chuyên biệt + implementation hiện tại.

---

## 27. Hotspots — file nên đọc trước khi sửa

| Task | Files cần đọc đầu tiên |
|---|---|
| Generate trip | `TripController`, `TripServiceImpl`, `AiGenerateServiceImpl`, `AiItineraryValidator`, `TripGenerationPersistenceServiceImpl` |
| Async generate | `TripController`, `AsyncTripGenerator`, `TripGenerationLock`, `TripGenerationStatusTracker` |
| Credits/Premium | `User`, `CreditServiceImpl`, `EntitlementPolicy`, `EntitlementServiceImpl`, `TripGenerationPersistenceServiceImpl` |
| Payment | `OrderServiceImpl`, `PaymentServiceImpl`, SePay controllers/config/repos |
| Place data | `PlaceEnrichmentServiceImpl`, `PlaceCacheServiceImpl`, `PlaceCache`, Goong/Serp clients |
| Background enrichment | `TripServiceImpl`, `TripEnrichmentScheduler`, `TripRepository`, `PlaceEnrichmentServiceImpl` |
| Activity swap | `ActivityController`, `ActivityAlternativeServiceImpl`, `ActivityAlternativeSessionRepository` |
| Social | `TripSocialController`, `TripSocialServiceImpl`, like/comment repos, notification events |
| Collaboration | `TripInviteController`, `TripMemberController`, `TripMemberServiceImpl` |
| Auth | `AuthController`, `AuthServiceImpl`, `JwtAuthFilter`, `SecurityConfig` |
| Notifications | `NotificationEventListener`, `NotificationServiceImpl`, WebSocket config/interceptor, schedulers |
| Chat | `ChatController`, `AdminChatController`, `ChatServiceImpl`, `R2StorageServiceImpl` |
| PDF | `TripExportServiceImpl` |
| Admin metrics | `AdminStatsController`, `StatsServiceImpl`, `AnalyticsServiceImpl` |

---

## 28. DO NOT BREAK — invariants cho AI

1. **Premium != role.** Premium suy ra từ paid credit balance; role chỉ USER/ADMIN RBAC.
2. **Không trừ generate credit trong validate/controller.** Atomic consume nằm trong persist transaction.
3. **Không giữ transaction mở xuyên LLM/maps HTTP calls.** Generation orchestration cố ý `NOT_SUPPORTED`.
4. **Không bỏ `TripGenerationLock`.** Nó chống sync/async generate trùng và credit/provider race.
5. **Không biến enrichment durable thành in-memory-only.** `enrichmentPending` + retry/lease/sweeper phục hồi sau restart/drop.
6. **Không coi provider transient failure là negative cache permanent.** 429/timeout/5xx phải degrade/retry theo resilience rules.
7. **Không bypass ownership/member guards** bằng generic repository lookup ở private resources.
8. **Không bỏ webhook idempotency.** SePay có thể gửi duplicate/concurrent events.
9. **Không tin FE cho entitlement/limits.** BE phải enforce trip days/styles/credits/premium feature gates.
10. **Không đổi AI response schema một phía.** Validator, persistence mapper và DTO/FE contract phụ thuộc nhau.
11. **Không freeze toàn bộ background place data vào Activity nếu live PlaceCache fallback đang được dùng.** Điều này ảnh hưởng ảnh/review xuất hiện sau enrichment.
12. **Không refactor hotspot chỉ vì file lớn** khi task không yêu cầu; các file lớn chứa nhiều business invariants tích lũy.
13. **Giữ timezone business reset/validation là `Asia/Ho_Chi_Minh`** cho daily trial/date rules.
14. **Giữ `open-in-view=false` assumption.** Load/map data trong đúng boundary, tránh lazy access muộn.
15. Khi thêm/chỉnh entity/enums/provider state, kiểm tra migrations/startup fixers tương ứng.

---

## 29. Mental model ngắn nhất

Nếu chỉ nhớ 10 dòng:

1. Spring Boot modular monolith + MSSQL + JWT.
2. `trip` là domain trung tâm; `ai` tạo itinerary; `place/geocoding` biến tên địa điểm thành dữ liệu thực.
3. Generate = validate -> LLM -> resolve place -> short atomic persist -> background enrich.
4. Generate credit chỉ bị trừ trong atomic persist, không trừ trước LLM.
5. Premium = paid AI credits > 0, không phải role.
6. Day 1 place FULL sync; day 2+ BASIC rồi FULL background.
7. Background enrichment durable bằng DB pending/retry/lease + scheduler.
8. SePay webhook idempotent và atomic khi cộng credit.
9. WebSocket phục vụ async generation/notification; STOMP CONNECT mới authenticate.
10. Trước khi sửa, đọc đúng hotspot; đừng quét/refactor toàn repo nếu file này đã định tuyến được task.
