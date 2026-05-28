# ChipTrip Backend

AI Travel Planner — REST API backend cho ứng dụng lên kế hoạch du lịch thông minh tại thị trường Việt Nam.

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21 |
| Framework | Spring Boot 4.0.6 |
| Database | Microsoft SQL Server |
| ORM | Spring Data JPA + Hibernate |
| Security | Spring Security 6 + JWT (JJWT 0.12.6) |
| Mapping | MapStruct 1.6.3 |
| Docs | SpringDoc OpenAPI 2.8.8 |
| Build | Maven |

## Cấu trúc dự án

```
src/main/java/com/tranbac/chiptripbe/
├── common/
│   ├── config/         # SecurityConfig, JpaAuditingConfig, DataSeeder
│   ├── entity/         # BaseEntity, BaseAuditEntity
│   ├── enums/          # RoleName, ActivityType, ChecklistCategory
│   ├── exception/      # AppException, GlobalExceptionHandler
│   ├── response/       # ApiResponse<T>, ErrorResponse
│   └── security/       # JwtProvider, JwtAuthFilter, UserPrincipal, ...
└── module/
    ├── auth/           # AuthController, AuthService, RefreshToken
    ├── user/           # User, Role
    ├── trip/           # Trip, TripDay, Activity, ChecklistItem
    └── ai/             # AiUsage
```

## Auth API

| Method | Endpoint | Auth | Mô tả |
|---|---|---|---|
| POST | `/api/v1/auth/register` | Public | Đăng ký tài khoản |
| POST | `/api/v1/auth/login` | Public | Đăng nhập |
| POST | `/api/v1/auth/refresh` | Public | Làm mới access token |
| POST | `/api/v1/auth/logout` | Bearer | Đăng xuất (revoke tokens) |

### Request / Response mẫu

**Login**
```json
POST /api/v1/auth/login
{
  "email": "user@example.com",
  "password": "password123"
}
```

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "uuid-string",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "userId": 1,
    "email": "user@example.com",
    "fullName": "Nguyễn Văn A"
  }
}
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

Điền thông tin vào `.env`:

```env
DB_URL=jdbc:sqlserver://<host>:1433;databaseName=chiptrip;encrypt=true;trustServerCertificate=true
DB_USERNAME=your_username
DB_PASSWORD=your_password

# Tối thiểu 32 ký tự
JWT_SECRET=your-super-secret-key-at-least-32-chars
```

### 3. Chạy ứng dụng

```bash
./mvnw spring-boot:run
```

### 4. Swagger UI

```
http://localhost:8080/swagger-ui.html
```

## Cấu hình JWT

| Tham số | Giá trị mặc định | Mô tả |
|---|---|---|
| `access-token-expiry-ms` | `900000` | Access token hết hạn sau 15 phút |
| `refresh-token-expiry-ms` | `604800000` | Refresh token hết hạn sau 7 ngày |

## Tài khoản Admin mặc định

Được seed tự động khi khởi động lần đầu:

| Field | Giá trị |
|---|---|
| Email | `admin@chiptrip.local` |
| Password | `ChangeMe@123` |

> Đổi mật khẩu ngay sau lần đăng nhập đầu tiên.

## CORS

Mặc định cho phép origins:
- `http://localhost:3000`
- `http://localhost:5173`

Chỉnh trong `application.yml` → `app.cors.allowed-origins`.
