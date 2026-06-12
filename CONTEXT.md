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
- **Java 21 với Spring Boot 4.0.6** (xác minh `pom.xml`: `java.version=21`, parent `spring-boot-starter-parent:4.0.6`)
- **Spring Web MVC** (REST) + **Spring WebFlux/WebClient** (gọi Gemini, Goong, SerpApi, OpenWeather)
- **Spring Security** + **JWT** (JJWT 0.12.6)
- **Spring Data JPA** + Hibernate (`spring.jpa.hibernate.ddl-auto: update` — **chưa dùng Flyway**)
- **Microsoft SQL Server** (DB chính — driver `com.microsoft.sqlserver:mssql-jdbc`, dialect `SQLServerDialect`)
- **Spring Mail** (SMTP) + **Spring WebSocket/STOMP** (chat + notification realtime)
- **MapStruct 1.6.3** (Entity ↔ DTO mapper) + **Lombok**
- **Bean Validation** (Jakarta Validation)
- **bucket4j 8.10.1** (rate limit — in-memory `ConcurrentHashMap`)
- **openhtmltopdf 1.1.37** (xuất lịch trình PDF)
- **AWS SDK v2 S3 client** dùng cho **Cloudflare R2** (lưu ảnh chat hỗ trợ)
- **JUnit 5 + Mockito + Testcontainers (MSSQL)** (test)
- **SpringDoc OpenAPI 2.8.8** (sinh Swagger từ code)

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
- **Goong REST API** (`https://rsapi.goong.io`) — Forward Geocode V2 (`has_vnid`), Autocomplete, Place Detail, Direction, Distance Matrix, Reverse Geocode (proxy qua `/api/v1/routes/*`).
- **Goong Map JS** (hiển thị bản đồ FE — token riêng có domain restriction).
- **SerpApi** (`https://serpapi.com`) — engine `google_maps` / `google_maps_photos` / `google_maps_reviews` (rating, ảnh, giờ mở cửa, reviews) + `google_hotels` (giá phòng + bookingUrl).
- **OpenWeather API** (dự báo thời tiết — `${OPENWEATHER_API_KEY}` optional).

### Storage
- **Cloudflare R2** (S3-compatible, gọi qua AWS SDK v2) — lưu ảnh upload từ luồng **Support Chat** (user ↔ admin). Cấu hình ở `app.r2.*` trong `application.yml`.

### Infra & DevOps
- **GitHub** (source) — chưa chốt CI/CD pipeline, nền tảng deploy FE/BE và DB cloud.
- Các hạng mục **Vercel / Netlify / Railway / Render / Sentry / Google Analytics / Internal Testing**: chưa chốt trong code.

---

## 4. Kiến trúc tổng thể

```
                ┌──────────────────┐         ┌──────────────────┐
                │  Web (React+TS)  │         │ Mobile (Flutter) │
                └──────┬───────────┘         └──────────┬───────┘
                       │  HTTPS                         │
                       │  (JWT trong Authorization)     │
                       ▼                                ▼
                ┌───────────────────────────────────────────────┐
                │      Backend — Spring Boot 4 (REST + WS)      │
                │  Controller → Service → Repository            │
                │  Auth · Trip · AI · Place · Notification ·    │
                │  Chat · External (Weather/Routes)             │
                └───────┬───────────────────┬───────────────────┘
                        │                   │
                        ▼                   ▼
                ┌────────────┐      ┌──────────────────────────┐
                │  MSSQL     │      │ External APIs            │
                │ (Hibernate │      │ - LLM (Gemini 2.5 Flash) │
                │  ddl-auto: │      │ - Goong (geocode/routes) │
                │   update)  │      │ - SerpApi (enrichment +  │
                └────────────┘      │   Google Hotels)         │
                                    │ - OpenWeather            │
                                    │ - Cloudflare R2 (chat)   │
                                    └──────────────────────────┘
```

**Nguyên tắc:** Web và App **dùng chung 1 backend duy nhất**. API contract (OpenAPI) là "hợp đồng" — chốt sớm để 3 nhánh BE/FE/App có thể chạy song song.

---

## 5. Domain Model (lược đồ chính)

> **Khóa chính (PK) thống nhất là `Long`** (auto-increment, `BaseEntity.id`). KHÔNG dùng UUID.
> Mọi entity đều extends `BaseEntity` (id) hoặc `BaseAuditEntity` (id + createdAt + updatedAt).

```
Role (1) ──< (n) User (1) ──< (n) Trip (1) ──< (n) TripDay (1) ──< (n) Activity ──> (0..1) PlaceCache
                   │                │                                    
                   │                ├──< (n) ChecklistItem
                   │                ├──< (n) TripMember
                   │                └──< (n) TripExpense
                   ├──< (n) AiUsage  (trip nullable — giữ audit khi trip bị xoá)
                   ├──< (n) Notification (recipient)
                   ├──< (1) Conversation ──< (n) ChatMessage
                   ├──< (n) RefreshToken / EmailVerificationToken / PasswordResetToken / OtpCode
```

### Bảng & trường chính (kiểm tra từ entity Java tại `module/**/entity/*.java`)

**Role** (`module/user/entity/Role.java`)
- `id` (Long, PK), `name` (string, unique) — vd `ROLE_USER`, `ROLE_ADMIN`

**User** (`module/user/entity/User.java`)
- `id` (Long, PK)
- `email` (unique constraint `uk_users_email`)
- `passwordHash` (BCrypt)
- `fullName` (`@Nationalized`, nullable=false)
- `avatarUrl` (nullable)
- `aiCredits` (Integer, default 3) — số lượt AI còn lại
- `isActive` (Boolean, default true) — `UserPrincipal.isEnabled()` đọc trường này
- `emailVerified` (Boolean, default false)
- `role` (`@ManyToOne Role`, eager) — **một user có đúng một role**
- `oauthProvider` (enum `OAuthProvider`, nullable), `oauthProviderId` (nullable) — dùng cho Google Login
- `preferences` (`@Nationalized`, JSON string, nullable)
- `lastLoginAt` (LocalDateTime, nullable)
- `createdAt`, `updatedAt` (từ `BaseAuditEntity`)

> **RBAC:** JWT claim `role` lưu tên role dạng string (vd `"ROLE_ADMIN"`). `@PreAuthorize("hasRole('ADMIN')")` đặt ở **class level** trên mọi admin controller; `SecurityConfig` cũng bảo vệ `/api/v1/admin/**` với `.hasRole("ADMIN")`. Role và admin mặc định được seed qua `DataSeeder` (`common/config/DataSeeder.java`).

**Trip** (`module/trip/entity/Trip.java`)
- `id` (Long, PK)
- `user` (FK `users`)
- `title` (`@Nationalized`) — AI sinh, vd "Hành trình Đà Nẵng 3 ngày"
- `departure`, `destination` (`@Nationalized`)
- `dateStart`, `dateEnd` (LocalDate)
- `peopleCount` (Integer)
- `budgetVnd` (Long, VNĐ) — **tên cột thật**, KHÔNG phải `budget`
- `styles` (String, JSON serialized) — gu du lịch: `["food_tour", "couple", ...]`
- `shareToken` (string, unique, nullable) — bật/tắt chia sẻ
- `inviteToken` (string, unique, nullable) — link mời thành viên (pattern như shareToken; null = chưa bật/đã thu hồi)
- `totalCost` — **DERIVED, tính từ `Activity.costVnd`**, không lưu cứng (xem `TripServiceImpl.buildTripDetailResponse`)
- `createdAt`, `updatedAt`
- Quan hệ: `days` (OneToMany TripDay), `checklist` (OneToMany ChecklistItem), `members` (OneToMany TripMember)

**TripDay** (`module/trip/entity/TripDay.java`, table `trip_days`)
- `id` (Long, PK), `trip` (FK), `dayNumber` (Integer), `date` (LocalDate)
- `dayCost` — DERIVED (SUM `Activity.costVnd`)

**Activity** (`module/trip/entity/Activity.java`)
- `id` (Long, PK), `day` (FK TripDay)
- `startTime` (LocalTime) — **tên cột thật**, KHÔNG phải `time`
- `name` (`@Nationalized`), `description` (`@Nationalized`, length 1000)
- `type` (enum `ActivityType`: FOOD, ATTRACTION, ACCOMMODATION, TRANSPORT, OTHER)
- `costVnd` (Long, default 0) — **tên cột thật**, KHÔNG phải `cost`
- `latitude`, `longitude` (BigDecimal precision 9, scale 6)
- `imageUrl` (nullable), `bookingUrl` (nullable)
- `displayOrder` (Integer)
- `searchQuery` (`@Nationalized`) — AI sinh để backend geocode
- `placeId` (Goong place id), `formattedAddress` (`@Nationalized`), `geocodingProvider` (vd `"goong"`)
- `placeCacheId` (Long, FK mềm sang `place_cache.id`, nullable nếu không geocode được)

**ChecklistItem** (`module/trip/entity/ChecklistItem.java`)
- `id` (Long, PK), `trip` (FK)
- `category` (enum `ChecklistCategory`: PAPERS, CLOTHES, HYGIENE, OTHER)
- `name` (`@Nationalized`), `isChecked` (Boolean, default false), `displayOrder` (Integer)

**TripMember** (`module/trip/entity/TripMember.java`)
- `id` (Long, PK), `trip` (FK), `user` (FK, nullable cho guest), `displayName` (`@Nationalized`), `role` (enum `TripMemberRole`)

**TripExpense** (`module/trip/entity/TripExpense.java`)
- `id` (Long, PK), `trip` (FK), `paidBy`, `title` (`@Nationalized`), `amount` (Long, VNĐ), `category`, `splitAmong` (JSON string)

**PlaceCache** (`module/place/entity/PlaceCache.java`, table `place_cache`)
- Cache kết quả Goong geocode + SerpApi enrichment, dùng lại giữa các trip để giảm call API ngoài và hiển thị "place card".
- `id`, `name`, `normalizedName`, `normalizedDestination`, `address`
- `latitude`, `longitude`
- `goongPlaceId`, `provinceName`, `communeName`
- `bookingUrl`, `pricePerNightVnd` (cho ACCOMMODATION, từ SerpApi Google Hotels)
- `serpDataId`, `serpPlaceId`, `serpTitle`, `placeType`, `hoursText`, `openState`
- `rating` (BigDecimal precision 3,1), `reviewCount`
- `openingHoursJson`, `photosJson`, `reviewsJson` (NVARCHAR(MAX))
- `phone`, `website`
- `lastSyncedAt` (Goong), `serpSyncedAt`, `serpEnriched` (bool)

**AiUsage** (`module/ai/entity/AiUsage.java`, table `ai_usages`)
- `id`, `user` (FK), `trip` (FK nullable — set null khi trip bị xoá để giữ audit log)
- `provider` (string, vd `"gemini"`)
- `tokensIn`, `tokensOut` (Integer)
- `costUsd` (BigDecimal precision 10, scale 6)
- `createdAt` (set ở `@PrePersist`)

**Notification** (`module/notification/entity/Notification.java`)
- `id`, `recipient` (FK User), `type` (enum `NotificationType`: `TRIP_MEMBER_ADDED`, `TRIP_REMINDER`, `AI_CREDITS_LOW`, `WEATHER_ALERT`, `SUPPORT_REPLY`, `NEW_SUPPORT_MESSAGE`, `TRIP_LIKED`, `TRIP_COMMENTED`, `POST_TRIP_REVIEW`)
- `title`, `body` (`@Nationalized`)
- `refId` (Long, polymorphic — vd tripId), `isRead` (boolean)
- Index `(user_id, is_read, created_at)`. Chi tiết thiết kế xem `docs/notification-feature.md`.

**Social — TripLike / TripComment** (`module/trip/entity/*.java`) — tính năng trip công khai
- `Trip` bổ sung: `isPublic` (bit, default 0), `publishedAt` (nullable), `likesCount` / `commentsCount` (denormalized counter — **tuyệt đối không SELECT COUNT(\*) mỗi lần load feed**, cập nhật atomic qua `TripRepository.updateLikesCount/updateCommentsCount`). Index `(is_public, published_at)` cho feed.
- `TripLike`: `tripId` (Long thuần), `userId`, `createdAt`. Unique `(trip_id, user_id)`. Toggle qua `TripSocialService`.
- `TripComment`: `tripId`, `user` (ManyToOne), `parentId` (nullable — NULL=gốc, có giá trị=reply, nested không giới hạn độ sâu), `content` (`@Nationalized` 1000), `createdAt/updatedAt`. **KHÔNG** map `@OneToMany children` — load toàn bộ replies 1 query rồi dựng tree in-memory (`getComments`). @mention lưu raw text, FE tự render.
- `tripId`/`userId` là cột Long thuần (không FK cascade) → xóa trip phải cleanup tay ở `TripServiceImpl`/`AdminTripServiceImpl`.

**Status vòng đời chuyến** — DERIVED, không lưu cột: `TripLifecycleStatus.of(dateStart, dateEnd, today)` → `UPCOMING` / `ONGOING` / `COMPLETED`. Trả trong `TripSummaryResponse.status` & `TripDetailResponse.status`. FE chia tab ở SavedPlans + auto-focus ngày hôm nay khi ONGOING.

**PlaceReview** (`module/place/entity/PlaceReview.java`, table `place_reviews`) — đánh giá ChipTrip của user, TÁCH BIỆT với reviews Google (PlaceCache.reviewsJson)
- `placeCacheId` (Long), `user` (FK), `rating` (TINYINT 1-5), `content` (`@Nationalized` 500), `createdAt`. Unique `(place_cache_id, user_id)` — mỗi user 1 review/địa điểm; POST trùng → UPDATE (upsert).

**ContentReport** (`module/moderation/entity/ContentReport.java`, table `content_reports`) — báo cáo & kiểm duyệt UGC
- `reporterUserId`, `targetType` (enum `ReportTargetType`: TRIP_COMMENT / PUBLIC_TRIP), `targetId`, `reason` (nullable), `status` (enum `ReportStatus`: PENDING / RESOLVED / DISMISSED), `resolvedByAdminId`, `createdAt`, `resolvedAt`.
- Admin resolve DELETE_CONTENT → xóa comment (qua `TripSocialService.adminDeleteComment`) hoặc gỡ public trip (`adminUnpublishTrip`), đồng thời đóng mọi report PENDING cùng target.

**Conversation / ChatMessage** (`module/chat/entity/*.java`) — hỗ trợ chat user ↔ admin
- `Conversation`: `user`, `status`, `lastMessageAt`, `lastReadByUserMsgId`, `lastReadByAdminMsgId`, `subject`, `assignedAdminId`
- `ChatMessage`: `conversation`, `sender`, `senderRole`, `messageType` (TEXT/IMAGE), `content`, `imageUrl`, `imageKey` (R2 object key)

**Auth tokens** (`module/auth/entity/*.java`)
- `RefreshToken`, `EmailVerificationToken`, `PasswordResetToken`, `OtpCode` — lưu SHA-256 hash (refresh), expiry, revoke.

---

## 6. API contract (chính)

Tất cả endpoint dưới `/api/v1/`. Auth qua header `Authorization: Bearer <JWT>` (trừ `/auth/*` và `/trips/shared/{token}`).
Danh sách dưới đây liệt kê các nhóm endpoint thật, đối chiếu từ `module/**/controller/*.java`.

### USER endpoints

**Auth** (`AuthController`, prefix `/auth`):
| Method | Endpoint | Mục đích |
|---|---|---|
| POST | `/auth/register` | Đăng ký |
| POST | `/auth/login` | Đăng nhập, trả JWT + refresh token |
| POST | `/auth/refresh` | Đổi refresh token lấy access token mới |
| POST | `/auth/logout` | Revoke refresh token |
| POST | `/auth/send-otp` | Gửi OTP về email |
| POST | `/auth/verify-otp` | Xác thực OTP |
| POST | `/auth/forgot-password` | Gửi link reset password |
| POST | `/auth/reset-password` | Đặt lại mật khẩu bằng token email |
| POST | `/auth/reset-password-with-otp` | Đặt lại mật khẩu bằng OTP |
| POST | `/auth/google` | Đăng nhập qua Google ID token |

**Users** (`UserController` + `UserAiUsageController`):
| Method | Endpoint | Mục đích |
|---|---|---|
| GET | `/users/me` | Hồ sơ + `aiCredits` còn lại |
| PATCH | `/users/me` | Sửa `fullName`, `avatarUrl` |
| POST | `/users/me/change-password` | Đổi mật khẩu |
| POST | `/users/me/resend-verification` | Gửi lại email xác nhận |
| DELETE | `/users/me` | Vô hiệu hoá tài khoản (soft delete) |
| GET | `/users/search?q=...` | Tìm user (dùng cho mời thành viên) |
| GET | `/users/me/ai-usage` | Lịch sử lượt AI đã dùng |

**Trips** (`TripController`):
| Method | Endpoint | Mục đích |
|---|---|---|
| POST | `/trips/generate` | **Sinh lịch trình bằng AI** (trừ 1 lượt AI sau khi persist thành công) |
| GET | `/trips` | Danh sách chuyến của user (phân trang) |
| GET | `/trips/{id}` | Chi tiết chuyến (days + activities + checklist + members) |
| PATCH | `/trips/{id}` | Sửa info chuyến |
| DELETE | `/trips/{id}` | Xoá chuyến |
| POST | `/trips/{id}/clone` | Nhân bản chuyến |
| POST | `/trips/{id}/share` | Bật chia sẻ → sinh `shareToken` |
| DELETE | `/trips/{id}/share` | Tắt chia sẻ |
| GET | `/trips/shared/{shareToken}` | **Public** — xem qua link chia sẻ |
| GET | `/trips/{id}/export/pdf` | Xuất lịch trình ra PDF (openhtmltopdf) |

**Trip Social** (`TripSocialController`) — trip công khai, like, comment:
| Method | Endpoint | Mục đích |
|---|---|---|
| GET | `/trips/public/feed?destination=&page=&size=` | **Public** — feed trip công khai (sort published_at DESC) |
| GET | `/trips/{tripId}/public` | **Public** — chi tiết trip công khai (404 nếu is_public=false) |
| PATCH | `/trips/{tripId}/publish` | Đăng/hủy công khai (`{ "isPublic": true/false }`) |
| POST | `/trips/{tripId}/like` | Toggle like → `{ liked, likesCount }` |
| GET | `/trips/{tripId}/like` | Trạng thái like hiện tại của tôi |
| GET | `/trips/{tripId}/comments` | **Public** — tree comment (root phân trang + children) |
| POST | `/trips/{tripId}/comments` | Thêm comment/reply (`{ content, parentId? }`) |
| DELETE | `/trips/{tripId}/comments/{commentId}` | Xóa comment (tác giả hoặc chủ trip) |

**Place Reviews** (`PlaceReviewController`) — đánh giá ChipTrip:
| Method | Endpoint | Mục đích |
|---|---|---|
| GET | `/places/{placeCacheId}/reviews` | **Public** — danh sách (phân trang) |
| GET | `/places/{placeCacheId}/reviews/summary` | **Public** — rating trung bình + tổng số |
| POST | `/places/{placeCacheId}/reviews` | Gửi đánh giá (`{ rating 1-5, content? }`) — upsert |
| DELETE | `/places/{placeCacheId}/reviews/{id}` | Xóa đánh giá của chính mình |

**Reports** (`ReportController`) — báo cáo nội dung:
| Method | Endpoint | Mục đích |
|---|---|---|
| POST | `/reports` | Báo cáo (`{ targetType: TRIP_COMMENT\|PUBLIC_TRIP, targetId, reason? }`) |

**Trip Invites** (`TripInviteController`) — mời thành viên qua link (viral loop):
| Method | Endpoint | Mục đích |
|---|---|---|
| POST | `/trips/{tripId}/invite` | Tạo link mời (owner only, **idempotent** — trả token cũ nếu đã có) |
| DELETE | `/trips/{tripId}/invite` | Thu hồi link (owner only) |
| GET | `/trips/invite/{inviteToken}` | **Public** — preview tối thiểu (title/destination/owner/memberCount/numDays — KHÔNG lộ ngày cụ thể) |
| POST | `/trips/join/{inviteToken}` | Tự tham gia làm MEMBER (409 nếu đã là thành viên) → noti owner |

**Activities / Checklist / Members / Expenses** (con của Trip):
| Method | Endpoint | Mục đích |
|---|---|---|
| POST/PATCH/DELETE | `/trips/{tripId}/days/{dayId}/activities[/...]` | CRUD activity |
| POST | `/trips/{tripId}/days/{dayId}/activities/reorder` | Sắp xếp lại |
| GET/POST/PATCH/DELETE | `/trips/{tripId}/checklist[/...]`, `/{itemId}/toggle` | CRUD + toggle |
| GET/POST/PATCH/DELETE | `/trips/{tripId}/members[/{memberId}]` | Quản lý thành viên |
| GET/POST/DELETE | `/trips/{tripId}/expenses[/{expenseId}]` | Chi phí chuyến đi |

**Places / Routes / Weather** (proxy ra Goong / SerpApi / OpenWeather):
| Method | Endpoint | Mục đích |
|---|---|---|
| GET | `/places/search?q=...` | Goong Autocomplete |
| GET | `/places/detail?placeId=...` | Goong Place Detail |
| GET | `/places/{id}` | Chi tiết PlaceCache đã enrich (rating, ảnh, giờ, reviews) |
| GET | `/routes/direction?oLat=&oLng=&dLat=&dLng=&vehicle=` | Goong Direction (proxy) |
| POST | `/routes/distance-matrix` | Goong Distance Matrix |
| GET | `/routes/reverse-geocode?lat=&lng=` | Reverse geocode → tên tỉnh/thành |
| GET | `/weather?city=...&from=...&to=...` | Dự báo thời tiết các ngày trong chuyến |

**AI Suggest** (`AiSuggestController`):
| Method | Endpoint | Mục đích |
|---|---|---|
| POST | `/ai/suggest-destinations` | Gợi ý 3-5 điểm đến phù hợp |

**Notifications** (`NotificationController`, REST + WebSocket STOMP):
| Method | Endpoint | Mục đích |
|---|---|---|
| GET | `/notifications` | Danh sách (phân trang) |
| GET | `/notifications/unread-count` | Số chưa đọc (cho badge) |
| PATCH | `/notifications/{id}/read` | Đánh dấu 1 noti đã đọc |
| PATCH | `/notifications/read-all` | Đánh dấu tất cả |
| WS | `/ws` (STOMP) + `/user/queue/notifications` | Push realtime (xem `docs/notification-feature.md`) |

**Support Chat** (`ChatController`, user side):
| Method | Endpoint | Mục đích |
|---|---|---|
| GET | `/chat/conversation` | Lấy / tạo conversation đang mở |
| GET | `/chat/conversation/messages` | Lịch sử tin nhắn |
| POST | `/chat/messages` | Gửi tin text |
| POST | `/chat/messages/image` (multipart) | Gửi ảnh (upload R2) |
| PATCH | `/chat/conversation/read` | Đánh dấu đã đọc |

### Admin endpoints (yêu cầu `ROLE_ADMIN`, prefix `/api/v1/admin/`)

**Users / Roles** (`AdminUserController`, `AdminRoleController`):
| Method | Endpoint | Mục đích |
|---|---|---|
| GET | `/admin/users` | Danh sách (filter: search, isActive, role) |
| GET | `/admin/users/{id}` | Chi tiết user |
| PATCH | `/admin/users/{id}` | Sửa `fullName` / `aiCredits` |
| PATCH | `/admin/users/{id}/status` | Bật/tắt (`{ "enabled": true/false }`) |
| PATCH | `/admin/users/{id}/activate` | Bật |
| PATCH | `/admin/users/{id}/deactivate` | Tắt (kèm revoke refresh tokens) |
| POST | `/admin/users/{id}/grant-credits` | Cộng AI credits (`{ "amount": N }`) |
| POST | `/admin/users/{id}/roles` | Gán role |
| DELETE | `/admin/users/{id}/roles/{roleId}` | Gỡ role |
| GET / POST / PATCH / DELETE | `/admin/roles[/{id}]` | Quản lý role |

**Trips / AI Usage / Stats** (`AdminTripController`, `AdminAiUsageController`, `AdminStatsController`):
| Method | Endpoint | Mục đích |
|---|---|---|
| GET | `/admin/trips` | Danh sách tất cả trip |
| GET | `/admin/trips/{id}` | Chi tiết trip |
| DELETE | `/admin/trips/{id}` | Xoá trip |
| GET | `/admin/ai-usages` | Log AI (filter userId, provider, from, to) |
| GET | `/admin/ai-usages/summary` | Tổng hợp chi phí AI theo provider/tháng |
| GET | `/admin/stats/dashboard` | Tổng quan |
| GET | `/admin/stats/users?from=&to=` | Số user đăng ký theo ngày |
| GET | `/admin/stats/trips?from=&to=` | Số trip tạo theo ngày |
| GET | `/admin/stats/ai-cost?from=&to=` | Chi phí AI theo provider/tháng |

**Support Chat (admin side)** (`AdminChatController`):
| Method | Endpoint | Mục đích |
|---|---|---|
| GET | `/admin/chat/conversations` | Danh sách hội thoại |
| GET | `/admin/chat/conversations/{id}/messages` | Lịch sử tin nhắn |
| POST | `/admin/chat/conversations/{id}/messages` | Gửi text |
| POST | `/admin/chat/conversations/{id}/messages/image` (multipart) | Gửi ảnh |
| PATCH | `/admin/chat/conversations/{id}/read` | Đánh dấu đã đọc |
| PATCH | `/admin/chat/conversations/{id}/close` | Đóng hội thoại |

**Moderation (admin)** (`AdminModerationController`):
| Method | Endpoint | Mục đích |
|---|---|---|
| GET | `/admin/reports?status=&page=&size=` | Danh sách báo cáo (lọc PENDING/RESOLVED/DISMISSED) |
| GET | `/admin/reports/pending-count` | Số báo cáo chờ xử lý (badge) |
| PATCH | `/admin/reports/{reportId}/resolve` | Xử lý (`{ action: DELETE_CONTENT\|DISMISS }`) |

**Quy ước response** (`ApiResponse<T>` tại `common/response/ApiResponse.java`):
```json
{
  "success": true,
  "message": "...",
  "data": { ... },
  "meta": { ... },
  "timestamp": "..."
}
```

**Quy ước lỗi** (`ErrorResponse` qua `GlobalExceptionHandler`): dùng HTTP status đúng nghĩa (400 validation, 401 auth, 403 forbidden, 404 not found, 429 rate-limit, 500 server). Body lỗi chuẩn:
```json
{ "success": false, "code": "RATE_LIMIT_EXCEEDED", "message": "...", "details": null, "timestamp": "..." }
```

---

## 7. Tích hợp AI — quy ước quan trọng

### 7.1. Luồng sinh lịch trình (theo code thật)

Orchestration ở `TripServiceImpl.generateTrip` (`module/trip/service/impl/TripServiceImpl.java`)
**KHÔNG** annotate `@Transactional` write — dùng `Propagation.NOT_SUPPORTED` để override class-level
`@Transactional(readOnly=true)`, tránh giữ DB connection trong khi gọi Gemini (HTTP dài) và geocode.

```
Client → POST /trips/generate (input)
   │
   ▼
TripServiceImpl.generateTrip()           (orchestration, KHÔNG @Transactional)
   │
   ├── 1. validateRequestAndCredits      (read-only tx ngắn)
   │       - findById(user); throw nếu aiCredits <= 0
   │       - dateStart >= today; dateEnd >= dateStart
   │
   ├── 2. AiService.generateItinerary    (NGOÀI transaction — gọi Gemini ~5-30s)
   │       AiGenerateServiceImpl:
   │       - Build system + user prompt (tiếng Việt, ép tên địa điểm thật)
   │       - responseMimeType=application/json + responseSchema strict
   │       - thinkingConfig.thinkingBudget=0 (tắt CoT của 2.5-flash, tránh timeout)
   │       - retry tối đa max-retries (mặc định 2) nếu 429/5xx/timeout/JSON parse fail
   │       - throw badRequest cho 400/401/403 (không retry)
   │
   ├── 3. AiItineraryValidator.validate  (fail-fast — không persist trip rác)
   │       - Đủ số ngày, dayNumber duy nhất, date trong range
   │       - Mỗi activity: name/time/type hợp lệ, costVnd >= 0
   │       - Geocodable type (FOOD/ATTRACTION/ACCOMMODATION/TRANSPORT) phải có searchQuery,
   │         KHÔNG được generic ("nhà hàng địa phương", "khách sạn trung tâm"...)
   │       - Tổng cost ≤ budget × 1.10 hoặc ≤ budget + 1.000.000đ
   │
   ├── 4. resolvePlaces                  (HTTP calls Goong + SerpApi, fail-soft per activity)
   │       Với mỗi activity geocodable & có searchQuery:
   │       - PlaceEnrichmentService.resolvePlace(searchQuery, destination)
   │           - PlaceCache lookup theo normalizedName + normalizedDestination
   │           - Cache miss → GoongClient.forwardGeocode V2 (has_vnid)
   │           - SerpApi enrich (rating/ảnh/giờ/reviews)
   │           - Upsert PlaceCache trong tx ngắn riêng
   │       - Nếu ACCOMMODATION: enrichAccommodation (SerpApi Google Hotels) lấy
   │         pricePerNightVnd + bookingUrl theo dayDate
   │       - Exception cho 1 activity → log warn, skip, trip vẫn được tạo
   │
   ▼
TripGenerationPersistenceServiceImpl.persistGeneratedTrip   (@Transactional — 1 tx ngắn)
   ├── Re-load user + RE-CHECK aiCredits (chống race với generate khác)
   ├── Save Trip
   ├── seedOwner (TripMember OWNER cho user)
   ├── Với mỗi day → save TripDay → save Activity (gán lat/lng/placeId/placeCacheId
   │   từ PlaceCache nếu resolve được; nếu ACCOMMODATION có pricePerNightVnd thì override cost)
   ├── Save ChecklistItem
   ├── ★ TRỪ aiCredits SAU KHI persist thành công ★
   │   - Nếu Gemini lỗi nặng / validate fail → đã throw từ trước, user KHÔNG mất lượt
   │   - Nếu remainingCredits <= 1 → publishEvent(AiCreditsLowEvent) → noti listener
   ├── Log AiUsage (provider="gemini", tokensIn/out, costUsd theo pricing)
   └── return TripGenerateResponse
```

**Vì sao trừ credit SAU khi persist thành công (khác sơ đồ tài liệu cũ)?**
Nếu trừ trước rồi rollback khi Gemini lỗi sẽ tạo ra trải nghiệm tệ với race condition và cần
giữ tx mở khi gọi LLM. Code hiện tại có chủ đích đảo lại: gọi Gemini ngoài tx → chỉ trừ credit
trong tx persist cuối cùng → fail-safe (user không mất lượt nếu Gemini/validate fail).

### 7.2. Yêu cầu prompt
- **System prompt:** đóng vai chuyên gia du lịch Việt Nam, output **chỉ JSON**, không markdown, không prose.
- **User prompt:** chứa input cấu trúc + schema JSON kỳ vọng.
- **Bắt buộc** dùng JSON mode / response_format json_schema để API tự enforce.
- Giá trị `cost` luôn là **integer VNĐ**, không có dấu phẩy/đơn vị.
- Tên địa điểm phải có thật ở Việt Nam, không bịa.

### 7.3. Schema JSON kỳ vọng (theo `AiGenerateServiceImpl.buildResponseSchema()` + DTO `AiItineraryResult`)

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
          "type": "FOOD|ATTRACTION|TRANSPORT|ACCOMMODATION|OTHER",
          "costVnd": 350000,
          "searchQuery": "Tên địa điểm cụ thể + tỉnh/thành. VD: 'Bánh căn Nhà Chung Đà Lạt'",
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

**Lưu ý quan trọng (khác với tài liệu cũ):**
- Field `costVnd` (INTEGER), **không phải** `cost`.
- **KHÔNG có `lat` / `lng`** trong output AI. Backend tự geocode qua Goong từ `searchQuery`.
- Mỗi activity geocodable (FOOD/ATTRACTION/ACCOMMODATION/TRANSPORT) phải có `searchQuery` đủ cụ thể —
  validator `AiItineraryValidator` từ chối các query generic ("nhà hàng địa phương",
  "khách sạn trung tâm", ...).
- `required` trong schema: `time, name, description, type, costVnd, searchQuery`.

### 7.4. Bảo mật API key

- **API key đặt trong biến môi trường**. Spring đọc qua `@ConfigurationProperties` / `@Value`:
  - `${GEMINI_API_KEY}` — bắt buộc (AI sinh lịch trình)
  - `${GOONG_API_KEY}` — bắt buộc (geocoding + routes + autocomplete)
  - `${SERPAPI_API_KEY}` — optional (enrichment + Google Hotels)
  - `${OPENWEATHER_API_KEY}` — optional (`/weather` endpoint)
  - `${R2_ENDPOINT}` / `${R2_ACCESS_KEY}` / `${R2_SECRET_KEY}` / `${R2_BUCKET_NAME}` / `${R2_PUBLIC_URL}` — cho chat upload ảnh
  - `${JWT_SECRET}`, `${DB_URL}` / `${DB_USERNAME}` / `${DB_PASSWORD}` — hạ tầng
- **Tuyệt đối không hardcode, không commit lên Git.**
- Frontend **không** gọi LLM trực tiếp; cũng **không** gọi Goong REST API trực tiếp — mọi call đều qua backend (`/api/v1/places/*`, `/api/v1/routes/*`).

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
| 5 | Tài khoản có 0 lượt AI vẫn generate được | `TripServiceImpl.validateRequestAndCredits` check `aiCredits > 0` trước khi gọi Gemini; sau khi LLM trả + validate + persist thành công, `TripGenerationPersistenceServiceImpl` **re-load user** và RE-CHECK credits trong tx persist (chống race) rồi mới trừ 1 lượt. Thiết kế có chủ đích: gọi Gemini NGOÀI transaction; trừ credit **SAU** khi persist OK → user không mất lượt nếu Gemini/validate fail. |
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
  - Rate limit `POST /trips/generate`: **đã implement** — 5 request/phút/user (`RateLimitFilter` + bucket4j, `app.rate-limit.generate-limit=5`, `generate-window-minutes=1`). Giới hạn login 5/15 phút, register 3/60 phút, forgot-password 3/60 phút, default 60/phút. **Lưu ý**: bucket lưu trong `ConcurrentHashMap` in-memory → chỉ đúng khi chạy 1 instance; muốn scale ra nhiều instance cần backend phân tán (Redis).
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
  - Gemini Structured Output: https://ai.google.dev/gemini-api/docs/structured-output
  - Goong REST API: https://docs.goong.io/rest/
  - SerpApi Google Maps / Hotels: https://serpapi.com/google-maps-api / https://serpapi.com/google-hotels-api

---

*File này là "single source of truth" về dự án. Khi có thay đổi quan trọng (đổi stack, thêm tính năng vào MVP, sửa API contract), cập nhật vào đây trước khi bắt tay code.*

---

## Changelog đồng bộ

### 2026-06-11 (đợt 2) — Cá nhân hóa AI, invite link, privacy trip public, guest feed

| Thay đổi | Căn cứ (file code) |
|---|---|
| **User.preferences vào prompt AI**: `AiService.generateItinerary(request, userPreferences)`; `formatPreferences()` map tag healing/food/photo/adventure → mô tả tiếng Việt, chèn block "Gu du lịch đã lưu" vào user prompt (không ghi đè Phong cách của chuyến) | `module/ai/service/impl/AiGenerateServiceImpl.java`, `TripServiceImpl.validateRequestAndCredits` trả preferences |
| **Invite link member**: `Trip.inviteToken` + `TripInviteController` (create idempotent/revoke/preview public/join) + noti `TripMemberJoinedEvent` cho owner; FE: nút "Mời qua link" trong GroupPanel + trang `/trips/join/:token` | `module/trip/controller/TripInviteController.java`, FE `features/explore/pages/JoinTripPage.tsx` |
| **Ẩn ngày cụ thể trên trip public** (quyền riêng tư): feed card + trang public chỉ hiện "N ngày M đêm", day tabs không hiện date | FE `features/explore/pages/{ExplorePage,TripPublicViewPage}.tsx` |
| **Guest vào feed**: MobileNav thêm link Khám phá (desktop Navbar đã có); /explore và /trips/:id/public không auth guard | FE `components/MobileNav.tsx` |
| **Auth đọc `state.from`**: sau login/Google login chuyển về đúng trang gọi (cần cho flow join-by-invite) thay vì luôn về `/` | FE `app/pages/Auth.tsx` |
| **Màn OTP sau đăng ký**: xác nhận ĐÃ CÓ SẴN từ trước (step `verify-email-otp` trong Auth.tsx) — không đổi gì | FE `app/pages/Auth.tsx` |
| **Rate limit DEV**: nới login 100/15p, register 50/60p, forgot 50/60p, generate 10/1p, default 300/1p — **hạ về giá trị cũ khi lên production** (comment trong yml) | `application.yml` `app.rate-limit` |
| Ghi nhận: AI runtime đã chuyển sang **OpenAI-compatible endpoint** (`app.ai.openai-compat.*`, `/chat/completions`, `response_format json_object`) — mục 7.3 mô tả Gemini schema strict là tài liệu cũ | `AiGenerateServiceImpl.buildRequest()`, `callLlm()` |

### 2026-06-11 — Tính năng Social, Reviews, Map labels + Đợt 1 (vòng đời + post-trip + moderation)

| Thay đổi | Căn cứ (file code) |
|---|---|
| **Public Trip + Social**: `Trip` thêm `isPublic/publishedAt/likesCount/commentsCount` + index `(is_public, published_at)`; entity `TripLike`, `TripComment` (nested), `TripSocialService` + `TripSocialController` | `module/trip/entity/{Trip,TripLike,TripComment}.java`, `module/trip/service/impl/TripSocialServiceImpl.java` |
| **User Reviews** tách biệt Google reviews: `PlaceReview` + `PlaceReviewController` (upsert, summary) | `module/place/entity/PlaceReview.java`, `module/place/controller/PlaceReviewController.java` |
| **Notification like/comment**: type `TRIP_LIKED`, `TRIP_COMMENTED` + event/listener (dedup like), publish trong `TripSocialServiceImpl` | `module/notification/service/NotificationEventListener.java` |
| **Vòng đời chuyến** (UPCOMING/ONGOING/COMPLETED) derived, field `status` trong Trip responses | `common/enums/TripLifecycleStatus.java` |
| **Post-trip touchpoint**: `PostTripScheduler` (cron 9:00, dateEnd+1) → noti `POST_TRIP_REVIEW` mời review & publish | `module/notification/scheduler/PostTripScheduler.java` |
| **Moderation**: module `moderation` (`ContentReport`, report user + admin resolve), tab Kiểm duyệt ở admin | `module/moderation/**`, FE `features/admin/AdminReports.tsx` |
| `PlaceController` tách logic parse JSON vào `PlaceQueryService` (đúng 3-layer) | `module/place/service/impl/PlaceQueryServiceImpl.java` |
| FE: trang Khám phá `/explore`, xem trip public `/trips/:id/public`, comment nested + @mention màu cam, code-split route, map marker có label tên địa điểm | `chip-trip-ai-planner/src/features/explore/**`, `components/GoongMap.tsx` |

> **Lưu ý DB**: tất cả bảng/cột mới do Hibernate `ddl-auto: update` tự tạo khi boot (chưa Flyway). FK cascade cho `trip_likes`/`trip_comments`/`place_reviews` (cột `tripId`/`userId` Long thuần) không được tạo tự động — code đã cleanup tay khi xóa trip. Script FK tham khảo: `docs/migrations/2026-06-11-social-and-reviews.sql`.

### 2026-06-09 — Đồng bộ tài liệu với code

Sửa ngày 2026-06-09 để khớp với code thực tế. Mọi thay đổi đều có căn cứ từ một file Java/yaml trong repo.

| Trước → Sau | Căn cứ (file code) |
|---|---|
| `Java 25+` → **`Java 21`** | `pom.xml` `<java.version>21</java.version>` |
| `Spring Boot 4.x` → **`Spring Boot 4.0.6`** | `pom.xml` parent version 4.0.6 |
| `msSQL (DB chính)` + nhắc tới **PostgreSQL / Neon / Supabase / Flyway** → **MSSQL** với `hibernate.ddl-auto: update` (chưa dùng Flyway) | `application.yml` `driver-class-name: com.microsoft.sqlserver.jdbc.SQLServerDriver`, `database-platform: SQLServerDialect`, `ddl-auto: update`; `pom.xml` dependency `mssql-jdbc`; không tồn tại folder `db/migration` |
| Bổ sung **bucket4j**, **openhtmltopdf**, **AWS SDK v2 S3 / Cloudflare R2**, **Spring Mail**, **Spring WebSocket** vào tech stack | `pom.xml` dependencies tương ứng |
| API ngoài chỉ nhắc Goong + SerpApi + OpenWeather (thay vì Google Maps API) | `application.yml` `app.goong.base-url=https://rsapi.goong.io`, `app.serpapi.base-url=https://serpapi.com`; `module/geocoding/client/GoongClient.java`; `module/geocoding/client/SerpApiClient.java` |
| AI **"chốt provider sau spike P0.6"** → **đã chốt Gemini 2.5 Flash** với `thinkingBudget=0` | `application.yml` `app.ai.gemini.model=gemini-2.5-flash`; `AiGenerateServiceImpl.buildGeminiRequest()` set `thinkingConfig.thinkingBudget=0` |
| Bổ sung **Cloudflare R2** cho upload ảnh chat | `application.yml` `app.r2.*`; `module/chat/entity/ChatMessage.java` field `imageKey`; `pom.xml` AWS SDK S3 |
| Infra liệt kê **Vercel/Netlify/Railway/Render/Neon/Supabase/Sentry/Google Analytics** → ghi rõ "chưa chốt" trừ GitHub | Không có file deploy/CI nào tham chiếu các dịch vụ này trong repo |
| Domain Model: **PK đổi từ UUID → `Long`** cho mọi entity | `common/entity/BaseEntity.java` `@GeneratedValue(strategy = IDENTITY)` + `Long id` |
| User: field `name` → **`fullName`**; bổ sung `oauthProvider`, `oauthProviderId`, `preferences` | `module/user/entity/User.java` |
| Trip: field `budget` → **`budgetVnd`**; bổ sung `shareToken`, `members` | `module/trip/entity/Trip.java` |
| Day → đổi tên thật là **`TripDay`** (table `trip_days`) | `module/trip/entity/TripDay.java` |
| Activity: `time` → **`startTime`**, `cost` → **`costVnd`**; bổ sung `searchQuery`, `placeId`, `formattedAddress`, `geocodingProvider`, `placeCacheId` | `module/trip/entity/Activity.java` |
| AiUsage: provider example `"openai"` → chỉ `"gemini"` | `TripGenerationPersistenceServiceImpl.logAiUsage()` hardcode `"gemini"` |
| Bổ sung **PlaceCache**, **TripMember**, **TripExpense**, **Notification**, **Conversation**, **ChatMessage**, **RefreshToken/Email/Password/Otp tokens** vào lược đồ | `module/place/entity/PlaceCache.java`, `module/trip/entity/TripMember.java`, `module/trip/entity/TripExpense.java`, `module/notification/entity/Notification.java`, `module/chat/entity/Conversation.java` & `ChatMessage.java`, `module/auth/entity/*.java` |
| API contract mở rộng: bổ sung **Auth (OTP/Google/Logout/reset-with-otp)**, **PATCH /trips/{id}/activities/{aid}** thay bằng path đúng `/trips/{tripId}/days/{dayId}/activities/{activityId}`, **Trip Members/Expenses/Clone/Share/Shared/Export PDF**, **Places detail / Place card / Routes (Direction/DistanceMatrix/ReverseGeocode)**, **AI suggest-destinations**, **Notifications (REST + WS)**, **Support Chat (user + admin)**, **Admin chat/role/activate/deactivate** | Liệt kê đầy đủ các `@RestController` trong `module/**/controller/*.java` |
| Quy ước response: từ `{ data, error }` → **`ApiResponse<T>` thực tế** với `success/message/data/meta/timestamp` | `common/response/ApiResponse.java` |
| **Luồng generate (mục 7.1)** viết lại theo code thật: orchestration **KHÔNG @Transactional** (Propagation.NOT_SUPPORTED), trừ credit **SAU** persist, có bước validate strict + geocode + enrichment | `TripServiceImpl.generateTrip` (line 84 `Propagation.NOT_SUPPORTED`), `AiItineraryValidator`, `PlaceEnrichmentService`, `TripGenerationPersistenceServiceImpl.persistGeneratedTrip` line 89-95 (trừ credit sau persist) |
| **JSON schema (mục 7.3)** sửa `cost` → `costVnd`, bỏ `lat`/`lng`, thêm `searchQuery` | `AiGenerateServiceImpl.buildResponseSchema()` line 154-203 |
| **API key (mục 7.4)**: bỏ `${OPENAI_API_KEY}`; ghi rõ `${GEMINI_API_KEY}`, `${GOONG_API_KEY}`, `${SERPAPI_API_KEY}`, `${OPENWEATHER_API_KEY}`, R2 keys, JWT/DB | `application.yml` toàn bộ block `app.*` |
| **Bug #5** cách xử lý: bỏ "trừ trước khi gọi LLM, rollback nếu LLM lỗi" → mô tả thiết kế thật (trừ sau khi persist OK, gọi LLM ngoài tx) | `TripServiceImpl.generateTrip` + `TripGenerationPersistenceServiceImpl` |
| **Rate limit** từ "tối đa 5/phút/user" (yêu cầu) → "đã implement" với in-memory limitation note | `common/config/RateLimitProperties.java`, `common/filter/RateLimitFilter.java` (bucket4j + ConcurrentHashMap) |
| Mục 14 bỏ link tham khảo OpenAI Structured Outputs, thêm link Goong + SerpApi | Không còn dùng OpenAI ở mọi nơi trong code |
