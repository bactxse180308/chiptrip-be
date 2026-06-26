# ChipTrip Backend

AI Travel Planner — REST API backend cho ứng dụng lên kế hoạch du lịch thông minh tại thị trường Việt Nam.

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21 |
| Framework | Spring Boot 4.0.6 |
| Database | Microsoft SQL Server |
| ORM | Spring Data JPA + Hibernate (`ddl-auto: update`) |
| Security | Spring Security 6 + JWT (JJWT 0.12.6) |
| AI | OpenAI-compatible endpoint (ShopAIKey proxy · model Gemini) — JSON mode / Structured Output |
| Realtime | Spring WebSocket / STOMP (chat + notification) |
| External APIs | Goong (geocoding/routes), SerpApi (place enrichment + Google Hotels), OpenWeather |
| Storage | Cloudflare R2 (S3-compatible, AWS SDK v2) — ảnh chat |
| Mapping | MapStruct 1.6.3 |
| Mail | Spring Mail (SMTP) — verify email, reset password, OTP |
| PDF | openhtmltopdf 1.1.37 — xuất lịch trình |
| Rate limit | bucket4j 8.10.1 (in-memory) |
| Docs | SpringDoc OpenAPI 2.8.8 (Swagger UI) |
| Build | Maven |

## Cấu trúc dự án

```
src/main/java/com/tranbac/chiptripbe/
├── common/
│   ├── config/         # SecurityConfig, JpaAuditingConfig, AiProperties, GoongProperties, SerpApiProperties
│   ├── entity/         # BaseEntity, BaseAuditEntity
│   ├── enums/          # RoleName, ActivityType, ChecklistCategory
│   ├── exception/      # AppException, GlobalExceptionHandler
│   ├── filter/         # RequestLoggingFilter, RateLimitFilter
│   ├── response/       # ApiResponse<T>, ErrorResponse
│   └── security/       # JwtProvider, JwtAuthFilter, UserPrincipal, ...
└── module/
    ├── auth/           # AuthController, AuthService, RefreshToken, OTP
    ├── user/           # User, Role
    ├── trip/           # Trip, TripDay, Activity, ChecklistItem, TripMember, TripExpense
    ├── ai/             # AiService (Gemini), AiSuggestService, AiUsage
    ├── geocoding/      # GoongClient, SerpApiClient, GoongGeocodingService
    ├── place/          # PlaceCache, PlaceEnrichmentService
    ├── external/       # Weather (OpenWeather), Places search
    └── stats/          # Admin dashboard & analytics
```


## Cài đặt & Chạy

### Yêu cầu

- Java 21+
- Maven 3.9+
- Microsoft SQL Server

### 1. Clone repo

```bash
git clone https://github.com/bactxse180308/chiptrip-be.git
cd chiptrip-be
```

### 2. Tạo file `.env`

```bash
cp .env.example .env
```

Điền các biến vào `.env` (xem đầy đủ trong `.env.example`):

```env
# Hạ tầng (bắt buộc)
DB_URL=jdbc:sqlserver://<host>:1433;databaseName=chiptrip;encrypt=true;trustServerCertificate=true
DB_USERNAME=your_username
DB_PASSWORD=your_password
JWT_SECRET=your-super-secret-key-at-least-32-chars   # tối thiểu 32 ký tự

# AI sinh lịch trình (bắt buộc)
AI_API_KEY=your_ai_key
AI_BASE_URL=https://api.shopaikey.com/v1

# Geocoding + routes (bắt buộc)
GOONG_API_KEY=your_goong_rest_key

# Tùy chọn (có fallback nếu để trống)
SERPAPI_API_KEY=        # enrich rating/ảnh/giờ mở cửa + Google Hotels
OPENWEATHER_API_KEY=    # dự báo thời tiết
GOOGLE_CLIENT_ID=       # đăng nhập Google
MAIL_USERNAME=          # gửi email xác thực / reset / OTP
MAIL_PASSWORD=
```

### 3. Chạy ứng dụng

```bash
./mvnw spring-boot:run
```

- Ứng dụng chạy ở `http://localhost:8080`.
- API docs (Swagger UI): `http://localhost:8080/swagger-ui.html`.
- CORS: chỉnh `app.cors.allowed-origins` trong `application.yml`.
- Schema DB do Hibernate (`ddl-auto: update`) tự tạo khi khởi động — không cần migration thủ công.

> **Lưu ý (Windows):** nếu Tomcat lỗi UDS loopback do biến `TMP` quá dài, đặt `set TMP=C:\Temp` trước khi chạy `mvnw spring-boot:run`.

---

## Database Schema

PK thống nhất là `Long` (auto-increment). Mọi entity extends `BaseEntity` (id) hoặc `BaseAuditEntity` (id + `createdAt` + `updatedAt`). Hibernate `ddl-auto: update` tự sinh bảng khi boot.

### ERD (quan hệ chính)

```
Role (1) ──< (n) User (1) ──< (n) Trip (1) ──< (n) TripDay (1) ──< (n) Activity ──> (0..1) PlaceCache
                  │               │
                  │               ├──< (n) ChecklistItem
                  │               ├──< (n) TripMember
                  │               ├──< (n) TripExpense
                  │               ├──< (n) TripLike / TripComment   (trip công khai)
                  ├──< (n) AiUsage           (trip nullable — giữ audit khi trip bị xoá)
                  ├──< (n) Notification       (recipient)
                  ├──< (1) Conversation ──< (n) ChatMessage
                  ├──< (n) PlaceReview
                  └──< (n) RefreshToken / EmailVerificationToken / PasswordResetToken / OtpCode
```

### Bảng chính

| Bảng | Trường chính | Ghi chú |
|---|---|---|
| `roles` | id, name (unique) | `ROLE_USER`, `ROLE_ADMIN` |
| `users` | id, email (unique), passwordHash, fullName, avatarUrl, aiCredits, isActive, emailVerified, role (FK), oauthProvider/oauthProviderId, preferences | 1 user có đúng 1 role |
| `trips` | id, user (FK), title, departure, destination, dateStart, dateEnd, peopleCount, budgetVnd, styles (JSON), shareToken, inviteToken, isPublic, publishedAt, likesCount, commentsCount | `totalCost` derived (SUM Activity.costVnd) |
| `trip_days` | id, trip (FK), dayNumber, date | `dayCost` derived |
| `activities` | id, day (FK), startTime, name, description, type (enum), costVnd, latitude, longitude, imageUrl, bookingUrl, displayOrder, searchQuery, placeId, formattedAddress, placeCacheId | type: FOOD/ATTRACTION/ACCOMMODATION/TRANSPORT/OTHER |
| `checklist_items` | id, trip (FK), category (enum), name, isChecked, displayOrder | category: PAPERS/CLOTHES/HYGIENE/OTHER |
| `trip_members` | id, trip (FK), user (FK nullable), displayName, role (enum) | guest cho phép user null |
| `trip_expenses` | id, trip (FK), paidBy, title, amount, category, splitAmong (JSON) | chia tiền nhóm |
| `trip_likes` | tripId, userId, createdAt | unique (trip_id, user_id) |
| `trip_comments` | id, tripId, user (FK), parentId (nullable), content, createdAt/updatedAt | nested comment, @mention raw text |
| `place_cache` | id, name, normalizedName, address, latitude, longitude, goongPlaceId, rating, reviewCount, photosJson, reviewsJson, bookingUrl, pricePerNightVnd, serpEnriched | cache Goong + SerpApi |
| `place_reviews` | id, placeCacheId, user (FK), rating (1-5), content, createdAt | đánh giá ChipTrip (tách Google reviews); unique (place_cache_id, user_id) |
| `ai_usages` | id, user (FK), trip (FK nullable), provider, tokensIn, tokensOut, costUsd, createdAt | audit chi phí AI |
| `notifications` | id, recipient (FK), type (enum), title, body, refId, isRead | index (user_id, is_read, created_at) |
| `content_reports` | id, reporterUserId, targetType (enum), targetId, reason, status (enum), resolvedByAdminId | kiểm duyệt UGC |
| `conversations` / `chat_messages` | conversation: user, status, lastMessageAt · message: sender, senderRole, messageType, content, imageUrl, imageKey | support chat user ↔ admin |
| auth tokens | RefreshToken / EmailVerificationToken / PasswordResetToken / OtpCode | lưu SHA-256 hash, expiry, revoke |

> Tham khảo chi tiết entity tại `src/main/java/com/tranbac/chiptripbe/module/**/entity/*.java`. Script FK bổ sung cho social/reviews: `docs/migrations/2026-06-11-social-and-reviews.sql`.

---

## Team Members

| Họ và tên | MSSV |
|---|---|
| Trần Xuân Bắc | SE180308 |
| Hồ Đình Anh | SE180670 |
| Nguyễn Đình Hoàng | SE192682 |
