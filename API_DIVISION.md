# API_DIVISION.md — Phân công API cho 2 Backend Developer

> **Phạm vi:** ChipTrip MVP — đầy đủ endpoint cho cả **USER** (người dùng cuối) và **ADMIN** (quản trị hệ thống).
> **Base URL:** `/api/v1`. Tất cả endpoint trả về `ApiResponse<T>` chuẩn (xem `CONTEXT.md` mục 6).

---

## 1. Nguyên tắc chia việc

Chia theo **bounded context** để 2 dev ít đụng nhau:

| | **DEV 1 — Identity & Account** | **DEV 2 — Trip, AI & Content** |
|---|---|---|
| Trọng tâm | Định danh, xác thực, phân quyền, quản lý user/role | Lịch trình du lịch, AI generate, quản lý nội dung, thống kê |
| Module phụ trách | `auth/`, `user/` | `trip/`, `ai/`, `external/`, `stats/` |
| Số endpoint | ~24 (12 USER + 12 ADMIN) | ~30 (21 USER + 1 PUBLIC + 8 ADMIN) |
| Độ phức tạp chính | Spring Security, JWT, hash, email flow | Tích hợp LLM, prompt engineering, parse JSON, gọi Maps/Weather |

**Quy tắc đặt route:**
- USER endpoints → `/api/v1/<resource>/...` (vd `/trips/{id}`)
- ADMIN endpoints → `/api/v1/admin/<resource>/...` (vd `/admin/trips/{id}`)
- PUBLIC endpoints → giống USER nhưng `@PermitAll` (vd `/trips/shared/{token}`)

---

## 2. DEV 1 — Identity & Account Management

### 2.1. USER endpoints

| # | Method | Path | Mô tả | Auth | Module |
|---|---|---|---|---|---|
| 1 | `POST` | `/auth/register` | Đăng ký user mới (mặc định gán `ROLE_USER`) | Public | auth |
| 2 | `POST` | `/auth/login` | Đăng nhập → trả `accessToken` + `refreshToken` | Public | auth |
| 3 | `POST` | `/auth/refresh` | Đổi refresh token lấy access token mới | Public | auth |
| 4 | `POST` | `/auth/logout` | Revoke refresh token hiện tại | USER | auth |
| 5 | `POST` | `/auth/forgot-password` | Gửi email link reset password | Public | auth |
| 6 | `POST` | `/auth/reset-password` | Reset password bằng token trong email | Public | auth |
| 7 | `POST` | `/auth/verify-email` | Xác thực email (token từ email) | Public | auth |
| 8 | `POST` | `/auth/resend-verification` | Gửi lại email verify | USER | auth |
| 9 | `GET`  | `/users/me` | Lấy hồ sơ bản thân + `aiCredits` còn lại | USER | user |
| 10 | `PATCH` | `/users/me` | Cập nhật hồ sơ (`fullName`, `avatarUrl`) | USER | user |
| 11 | `POST` | `/users/me/change-password` | Đổi mật khẩu (cần old password) | USER | user |
| 12 | `DELETE` | `/users/me` | Vô hiệu hoá tài khoản (soft delete: `isActive=false`) | USER | user |

### 2.2. ADMIN endpoints

| # | Method | Path | Mô tả | Auth | Module |
|---|---|---|---|---|---|
| 13 | `GET`  | `/admin/users` | Danh sách user — `search`, `isActive`, `role`, phân trang | ADMIN | user |
| 14 | `GET`  | `/admin/users/{id}` | Chi tiết user | ADMIN | user |
| 15 | `PATCH` | `/admin/users/{id}` | Cập nhật (full_name, aiCredits) — KHÔNG sửa email/password trực tiếp | ADMIN | user |
| 16 | `PATCH` | `/admin/users/{id}/activate` | Kích hoạt tài khoản | ADMIN | user |
| 17 | `PATCH` | `/admin/users/{id}/deactivate` | Vô hiệu hoá (kèm revoke hết refresh token) | ADMIN | user |
| 18 | `POST` | `/admin/users/{id}/grant-credits` | Cộng lượt AI (body: `{amount: 10}`) | ADMIN | user |
| 19 | `POST` | `/admin/users/{id}/roles` | Gán role cho user (body: `{roleName: "ROLE_PREMIUM"}`) | ADMIN | user |
| 20 | `DELETE` | `/admin/users/{id}/roles/{roleId}` | Gỡ role | ADMIN | user |
| 21 | `GET`  | `/admin/roles` | Danh sách role | ADMIN | user |
| 22 | `POST` | `/admin/roles` | Tạo role mới | ADMIN | user |
| 23 | `PATCH` | `/admin/roles/{id}` | Cập nhật mô tả role | ADMIN | user |
| 24 | `DELETE` | `/admin/roles/{id}` | Xoá role (chỉ khi không có user nào đang dùng) | ADMIN | user |

### 2.3. Trách nhiệm hạ tầng kèm theo (Dev 1 setup tuần 3 — Dev 2 dùng)

- `common/security/JwtTokenProvider` — sinh & verify JWT
- `common/security/JwtAuthFilter` — đọc header `Authorization`
- `common/security/CustomUserDetailsService` + `UserPrincipal`
- `common/config/SecurityConfig` — cấu hình rule `/admin/**` → `hasRole('ADMIN')`, `/auth/**` permit, còn lại require auth
- `common/exception/GlobalExceptionHandler` + `AppException`
- `common/response/ApiResponse`, `ErrorResponse`, `PageMeta`
- `common/entity/BaseEntity`, `BaseAuditEntity`
- `common/config/JpaAuditingConfig`

> Đây là **shared infra** — Dev 2 sẽ import và dùng. Cần xong **trước tuần 4** để Dev 2 không bị block.

---

## 3. DEV 2 — Trip, AI & Content

### 3.1. USER endpoints

#### Trip lifecycle
| # | Method | Path | Mô tả | Auth | Module |
|---|---|---|---|---|---|
| 1 | `POST` | `/trips/generate` | **Sinh lịch trình bằng AI** — trừ 1 lượt, gọi LLM, lưu Trip/Day/Activity/Checklist | USER | trip + ai |
| 2 | `GET`  | `/trips` | Danh sách chuyến của mình (paginate, sort by `createdAt desc`) | USER | trip |
| 3 | `GET`  | `/trips/{id}` | Chi tiết chuyến (gồm days + activities + checklist) | USER (owner) | trip |
| 4 | `PATCH` | `/trips/{id}` | Cập nhật info chuyến (`title`, `dateStart`, `dateEnd`, `budget`) | USER (owner) | trip |
| 5 | `DELETE` | `/trips/{id}` | Xoá chuyến (cascade days/activities/checklist qua JPA) | USER (owner) | trip |
| 6 | `POST` | `/trips/{id}/clone` | Nhân bản chuyến (tạo bản copy mới với title mới) | USER (owner) | trip |
| 7 | `POST` | `/trips/{id}/share` | Bật chia sẻ → sinh `shareToken` | USER (owner) | trip |
| 8 | `DELETE` | `/trips/{id}/share` | Tắt chia sẻ (xoá `shareToken`) | USER (owner) | trip |

#### Quản lý activity (CRUD chi tiết trong chuyến)
| # | Method | Path | Mô tả | Auth | Module |
|---|---|---|---|---|---|
| 9 | `POST` | `/trips/{id}/days/{dayId}/activities` | Thêm activity vào ngày | USER (owner) | trip |
| 10 | `PATCH` | `/trips/{id}/days/{dayId}/activities/{activityId}` | Sửa activity (đổi thời gian, chi phí…) | USER (owner) | trip |
| 11 | `DELETE` | `/trips/{id}/days/{dayId}/activities/{activityId}` | Xoá activity | USER (owner) | trip |
| 12 | `POST` | `/trips/{id}/days/{dayId}/activities/reorder` | Sắp lại thứ tự (body: `{orderedIds: [...]}`) | USER (owner) | trip |

#### Checklist chuẩn bị đồ
| # | Method | Path | Mô tả | Auth | Module |
|---|---|---|---|---|---|
| 13 | `GET`  | `/trips/{id}/checklist` | Lấy checklist | USER (owner) | trip |
| 14 | `POST` | `/trips/{id}/checklist` | Thêm item | USER (owner) | trip |
| 15 | `PATCH` | `/trips/{id}/checklist/{itemId}` | Sửa tên/category | USER (owner) | trip |
| 16 | `PATCH` | `/trips/{id}/checklist/{itemId}/toggle` | Tick / untick | USER (owner) | trip |
| 17 | `DELETE` | `/trips/{id}/checklist/{itemId}` | Xoá item | USER (owner) | trip |

#### Export & External proxy
| # | Method | Path | Mô tả | Auth | Module |
|---|---|---|---|---|---|
| 18 | `GET`  | `/trips/{id}/export/pdf` | Xuất chuyến đi ra PDF | USER (owner) | trip |
| 19 | `GET`  | `/places/search?q=hà+nội` | Autocomplete tên địa điểm (proxy Goong REST) | USER | external |
| 20 | `GET`  | `/weather?city=...&from=...&to=...` | Dự báo thời tiết ngày trong chuyến | USER | external |
| 21 | `GET`  | `/users/me/ai-usage` | Lịch sử lượt AI đã dùng (số lần, chi phí) | USER | ai |

### 3.2. PUBLIC endpoint (không cần auth)

| # | Method | Path | Mô tả | Auth | Module |
|---|---|---|---|---|---|
| 22 | `GET`  | `/trips/shared/{shareToken}` | Xem chuyến đi qua link chia sẻ (read-only) | Public | trip |

### 3.3. ADMIN endpoints

| # | Method | Path | Mô tả | Auth | Module |
|---|---|---|---|---|---|
| 23 | `GET`  | `/admin/trips` | Tất cả chuyến — `userId`, `from`, `to`, paginate | ADMIN | trip |
| 24 | `GET`  | `/admin/trips/{id}` | Chi tiết chuyến của bất kỳ user nào | ADMIN | trip |
| 25 | `DELETE` | `/admin/trips/{id}` | Xoá chuyến vi phạm | ADMIN | trip |
| 26 | `GET`  | `/admin/ai-usages` | Log AI — filter `userId`, `provider`, `from`, `to` | ADMIN | ai |
| 27 | `GET`  | `/admin/stats/dashboard` | Tổng quan: tổng user, tổng trip, AI cost tháng này | ADMIN | stats |
| 28 | `GET`  | `/admin/stats/users?from=&to=` | Số user đăng ký theo ngày | ADMIN | stats |
| 29 | `GET`  | `/admin/stats/trips?from=&to=` | Số trip sinh theo ngày | ADMIN | stats |
| 30 | `GET`  | `/admin/stats/ai-cost?from=&to=` | Chi phí AI theo provider/tháng | ADMIN | stats |

---

## 4. Tổng kết phân công

| | DEV 1 | DEV 2 |
|---|---|---|
| Tổng endpoint | 24 (12 USER + 12 ADMIN) | 30 (21 USER + 1 PUBLIC + 8 ADMIN) |
| Module sở hữu | `auth/`, `user/` | `trip/`, `ai/`, `external/`, `stats/` |
| Entity sở hữu | `User`, `Role`, `RefreshToken` | `Trip`, `TripDay`, `Activity`, `ChecklistItem`, `AiUsage` |
| Tích hợp ngoài | Email service (SMTP) | Gemini, Goong REST, SerpApi, OpenWeather |
| Trách nhiệm chung | **Shared infra** (Security, ApiResponse, BaseEntity, ExceptionHandler) | — |

---

## 5. Thứ tự thực hiện (map vào timeline 14 tuần)

### Tuần 3 — Setup nền (Cùng làm, nhưng Dev 1 chủ đạo)
- **Dev 1:** Khởi tạo Spring Boot project · Cấu hình MSSQL · Setup `common/security` (SecurityConfig, JWT, AuthFilter) · Endpoint **#1 register**, **#2 login**
- **Dev 2:** Đọc CONTEXT + DATABASE_GUIDE · Pull code Dev 1 · Tạo entity `Trip`, `TripDay`, `Activity`, `ChecklistItem`, `AiUsage` · Repository tương ứng

### Tuần 4 — Auth hoàn chỉnh + Trip CRUD cơ bản
- **Dev 1:** Endpoint **#3 refresh**, **#4 logout**, **#9 /users/me**, **#10 PATCH /users/me**, **#11 change-password**
- **Dev 2:** Endpoint **#2 GET /trips**, **#3 GET /trips/{id}**, **#4 PATCH /trips/{id}**, **#5 DELETE /trips/{id}** (chưa cần AI, dùng dữ liệu mock)

### Tuần 5 — Admin user management + AI integration
- **Dev 1:** Endpoint **#13-17** quản lý user · **#21-24** quản lý role · **#18 grant-credits**
- **Dev 2:** Tích hợp LLM API (sau spike P0.6) · **Endpoint #1 POST /trips/generate** (luồng AI hoàn chỉnh) · **#21 /users/me/ai-usage**

### Tuần 6 — Email flow + Activity/Checklist
- **Dev 1:** Endpoint **#5 forgot-password**, **#6 reset-password**, **#7 verify-email**, **#8 resend-verification** (kèm SMTP setup)
- **Dev 2:** Endpoint **#9-12** quản lý activity · **#13-17** checklist · **#19 places/search** · **#20 weather**

### Tuần 7 — Share, Export, Admin content/stats, Test
- **Dev 1:** Endpoint **#12 DELETE /users/me** · **#19-20** assign/remove role · viết test cho auth · polish
- **Dev 2:** Endpoint **#6 clone**, **#7-8 share**, **#22 PUBLIC shared/{token}**, **#18 export PDF** · Endpoint admin **#23-30** trip + stats · viết test cho trip + AI

> **Mục tiêu Backend feature-complete cuối Tuần 7** = milestone M3 trong timeline. Dev 1 và Dev 2 daily standup 15 phút để chốt API contract khi có thay đổi.

---

## 6. Quy tắc Authorization (CỰC QUAN TRỌNG)

### Mặc định Spring Security
```java
// SecurityConfig
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/**").permitAll()
    .requestMatchers(HttpMethod.GET, "/api/v1/trips/shared/**").permitAll()
    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
    .anyRequest().authenticated()
);
```

### Ownership check (cho USER endpoints có `{id}` của tài nguyên)
- Mọi endpoint thao tác trên `Trip` của user phải kiểm `trip.userId == currentUser.id` ở **service layer**.
- Nếu không phải owner → ném `AppException(FORBIDDEN, "Bạn không có quyền với chuyến đi này")` → mapper trả về **HTTP 403**.
- KHÔNG để service trả về 404 "Trip not found" cho cả 2 trường hợp (không tồn tại / không phải owner) — đó là leak thông tin. Phân biệt rõ.

### ADMIN privilege
- Endpoint `/admin/**` **bỏ qua ownership check** (admin xem được mọi user/trip).
- Admin **KHÔNG nên** đọc `password_hash` (luôn map ra DTO không có field này).
- Mọi hành động `PATCH/DELETE` của admin nên log lại (audit) — TODO sau MVP.

### Rate limit (tầng filter chung — Dev 1 setup)
| Endpoint | Giới hạn |
|---|---|
| `POST /auth/login` | 5 lần / 15 phút / IP |
| `POST /auth/register` | 3 lần / 1 giờ / IP |
| `POST /auth/forgot-password` | 3 lần / 1 giờ / email |
| `POST /trips/generate` | 5 lần / 1 phút / user |
| Tất cả còn lại | 60 lần / 1 phút / user |

---

## 7. Coordination Points (điểm cần đồng bộ)

| Khi nào | Ai cần ai | Lý do |
|---|---|---|
| Cuối Tuần 3 | Dev 2 cần Dev 1 xong `JwtAuthFilter` + `UserPrincipal` | Để Dev 2 lấy `currentUser` trong service |
| Đầu Tuần 4 | Dev 2 cần entity `User` của Dev 1 | Trip có FK đến User |
| Tuần 5 | Cả 2 cần `ApiResponse`/`AppException` cố định | Format response đồng nhất |
| Tuần 5-6 | Dev 1 cần biết Dev 2 dùng claim gì trong JWT | Vd có cần claim `roles` trong token để decode ở FE không |
| Tuần 7 | Cả 2 review OpenAPI doc (Swagger UI) | Đảm bảo tài liệu khớp với code |

---

## 8. Checklist Definition of Done cho mỗi endpoint

Mỗi endpoint chỉ được coi là **DONE** khi:

- [ ] Controller có annotation đầy đủ (`@RestController`, `@RequestMapping`, Method)
- [ ] Validate input bằng Bean Validation (`@Valid` trên `@RequestBody`)
- [ ] Logic xử lý nằm ở Service (Controller chỉ delegate, không có business)
- [ ] Trả `ApiResponse<T>` chuẩn, không trả Entity trực tiếp (luôn qua DTO)
- [ ] Xử lý lỗi qua `AppException` + `GlobalExceptionHandler`
- [ ] Authorization check rõ ràng (ownership hoặc role)
- [ ] Có ít nhất 1 unit test cho service method chính
- [ ] Đã test thủ công bằng Postman (cả happy path + 1 error case)
- [ ] OpenAPI annotation (`@Operation`, `@ApiResponse`) cho Swagger
- [ ] Đã merge vào `dev` branch (qua PR có reviewer)

---

## 9. Endpoint chưa làm ở MVP (note để sau)

Các endpoint sau **chưa cần** ở MVP — để Phase 2:
- Payment / subscription (mua gói Premium → cộng credits)
- Social login (Google/Facebook OAuth2)
- Comment/Review cho địa điểm
- Friend list / share riêng với người dùng cụ thể
- Notification (email/push khi sắp đến ngày đi)
- Multi-language (chỉ tiếng Việt ở MVP)

---

*File này là "hợp đồng" giữa 2 dev BE. Khi thêm/sửa/xoá endpoint, cập nhật file này trước rồi mới code.*

---

## Changelog đồng bộ

Sửa ngày 2026-06-09 để khớp với code thực tế.

| Trước → Sau | Căn cứ (file code) |
|---|---|
| `/places/search` mô tả "proxy Google Maps" → **"proxy Goong REST"** | `module/external/controller/ExternalController.java` line 28 + `module/external/service/ExternalApiService` gọi `GoongClient.autocomplete()` |
| "Tích hợp ngoài: LLM API, Google Maps, OpenWeather" → **"Gemini, Goong REST, SerpApi, OpenWeather"** | `application.yml` `app.ai.gemini.*`, `app.goong.*`, `app.serpapi.*`, `app.external.openweather-api-key` |
| Email service "(SMTP/SendGrid)" → **chỉ "SMTP"** | `application.yml` `spring.mail.*` (SMTP Gmail), không có SendGrid dependency trong `pom.xml` |
