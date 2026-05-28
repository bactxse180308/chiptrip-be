# Java Spring Boot Code Style Guide

Derived from the `mind-recharge-be` codebase. Intended for reuse across Java Spring Boot projects.

---

## Table of Contents

1. [Technology Stack](#technology-stack)
2. [Project Structure](#project-structure)
3. [Naming Conventions](#naming-conventions)
4. [Architecture Patterns](#architecture-patterns)
5. [Dependency Injection](#dependency-injection)
6. [API Design](#api-design)
7. [Exception Handling](#exception-handling)
8. [Logging](#logging)
9. [Security](#security)
10. [Database & Persistence](#database--persistence)
11. [Configuration Management](#configuration-management)
12. [Testing](#testing)
13. [Sensitive Data Handling](#sensitive-data-handling)

---

## Technology Stack

| Layer | Choice |
|-------|--------|
| Language | Java 21 |
| Framework | Spring Boot 4.x |
| Security | Spring Security 6.x + JWT (JJWT) |
| ORM | Spring Data JPA + Hibernate |
| Migrations | Flyway |
| Mapping | MapStruct 1.6+ |
| Boilerplate | Lombok |
| API Docs | Springdoc OpenAPI (Swagger UI) |
| Monitoring | Spring Actuator + Micrometer Prometheus |
| Build | Gradle (Kotlin DSL) |
| Testing | JUnit 5 + TestContainers |

---

## Project Structure

```
src/main/java/com/<org>/<app>/
├── common/
│   ├── config/          # Spring configuration beans
│   ├── entity/          # Shared base entities
│   ├── enums/           # Shared enums
│   ├── event/           # Application events
│   ├── exception/       # AppException + GlobalExceptionHandler
│   ├── filter/          # Servlet filters (JWT, rate limit)
│   ├── response/        # ApiResponse, ErrorResponse, PageMeta
│   ├── security/        # JWT provider, auth filter, user principal
│   └── util/            # Stateless utility classes
└── module/
    └── <domain>/        # One package per bounded context
        ├── controller/
        ├── dto/
        │   ├── request/
        │   └── response/
        ├── entity/
        ├── repository/
        ├── service/
        │   ├── <Domain>Service.java          # interface
        │   └── impl/
        │       └── <Domain>ServiceImpl.java  # package-private class
        └── specification/
```

**Rule:** each bounded-context module is self-contained. Cross-cutting concerns live in `common/`.

---

## Naming Conventions

### Classes

| Artifact | Pattern | Examples |
|----------|---------|----------|
| Entity | `PascalCase`, singular | `JournalEntry`, `DailyCheckin` |
| Request DTO | `<Verb><Domain>Request` | `CreateJournalRequest`, `LoginRequest` |
| Response DTO | `<Domain>Response` | `JournalResponse`, `AuthResponse` |
| Service interface | `<Domain>Service` | `JournalService` |
| Service impl | `<Domain>ServiceImpl` | `JournalServiceImpl` |
| Controller | `<Domain>Controller` | `JournalController` |
| Repository | `<Domain>Repository` | `JournalEntryRepository` |
| Specification | `<Domain>Specification` | `JournalEntrySpecification` |
| Config class | `<Purpose>Config` | `SecurityConfig`, `S3Config` |
| Event | `<Domain><Action>Event` | `JournalSavedEvent` |
| Filter | `<Purpose>Filter` | `JwtAuthenticationFilter`, `RateLimitFilter` |
| Enum | `PascalCase` | `MoodLevel`, `JourneyStatus` |

### Methods

| Layer | Convention | Examples |
|-------|-----------|----------|
| Service | CRUD verbs | `create`, `list`, `getById`, `update`, `delete` |
| Repository | Spring Data derived | `findByEmail`, `existsByEmail`, `findByIdAndUserId` |
| Specification | Predicate descriptors | `activeForUser(userId)`, `notDeleted()`, `dateRange(from, to)` |

### Variables & Constants

```java
// local / instance: camelCase
long userId = ...;
String moodLevel = ...;

// static final: CONSTANT_CASE
private static final int MAX_REQUESTS = 10;
private static final long WINDOW_MS = 60_000;
```

### Packages

- All lowercase, no underscores: `com.org.app.module.journal`

---

## Architecture Patterns

### Three-Layer Architecture

```
Controller → Service → Repository
```

- **Controllers** handle HTTP: request parsing, auth context, response wrapping. No business logic.
- **Services** own business logic: transactions, events, exceptions.
- **Repositories** handle persistence only; no business logic.

### Service Interface + Impl

Always define a service interface. The implementation class is package-private:

```java
// JournalService.java (public interface)
public interface JournalService {
    JournalResponse create(Long userId, CreateJournalRequest req);
    Page<JournalResponse> list(Long userId, Pageable pageable);
}

// impl/JournalServiceImpl.java (package-private)
@Service
@RequiredArgsConstructor
class JournalServiceImpl implements JournalService {
    private final JournalEntryRepository journalRepo;
    // ...
}
```

### Base Entity

All persistent entities extend `BaseEntity`:

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
```

Enable auditing: `@EnableJpaAuditing` on a config class.

### Soft Delete

Entities that support deletion carry a nullable `deletedAt`:

```java
@Column
private Instant deletedAt; // null = active

public void softDelete() {
    this.deletedAt = Instant.now();
}
```

All queries must filter via Specification:

```java
static Specification<JournalEntry> notDeleted() {
    return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
}
```

Never use `@SQLDelete` or `@Where` — explicit Specifications are transparent and composable.

### Specification Pattern (JPA Criteria)

Use Specifications instead of `@Query` for multi-condition queries:

```java
public class JournalEntrySpecification {

    public static Specification<JournalEntry> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<JournalEntry> forUser(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    public static Specification<JournalEntry> activeForUser(Long userId) {
        return notDeleted().and(forUser(userId));
    }

    public static Specification<JournalEntry> dateRange(LocalDate from, LocalDate to) {
        return (root, query, cb) -> cb.between(root.get("entryAt"), from, to);
    }
}
```

Usage:

```java
journalRepo.findAll(
    JournalEntrySpecification.activeForUser(userId)
        .and(dateRange(from, to)),
    pageable
);
```

### Event-Driven Cross-Cutting Concerns

Use `ApplicationEventPublisher` to decouple side effects (notifications, analytics, milestones) from the main flow:

```java
// Publish inside @Transactional service method
eventPublisher.publishEvent(new JournalSavedEvent(this, savedEntry));

// Listener (separate class)
@EventListener
@Async
public void onJournalSaved(JournalSavedEvent event) {
    // trigger sentiment analysis, streak update, etc.
}
```

---

## Dependency Injection

Always use **constructor injection**. Lombok `@RequiredArgsConstructor` generates the constructor automatically:

```java
@Service
@RequiredArgsConstructor
class CheckinServiceImpl implements CheckinService {
    private final DailyCheckinRepository checkinRepo;
    private final UserRepository userRepo;
    private final ApplicationEventPublisher eventPublisher;
}
```

- All injected fields are `private final`.
- Never use field injection (`@Autowired` on field).
- Never use setter injection unless required by a framework constraint.

For `@Value`-based config, inject via constructor parameter:

```java
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {
    @Value("${app.jwt.secret}")
    private String secret;
}
```

For groups of properties, use `@ConfigurationProperties`:

```java
@Configuration
@ConfigurationProperties(prefix = "app.admin")
@Getter @Setter
public class AdminConfigProperties {
    private String email;
    private String password;
    private String displayName;
}
```

---

## API Design

### URL Structure

```
/api/v1/<resource>               # collection (GET list, POST create)
/api/v1/<resource>/{id}          # single resource (GET, PATCH, DELETE)
/api/v1/admin/<resource>         # admin-only routes
/api/v1/auth/*                   # public auth endpoints
```

### Unified Response Wrapper

All endpoints return `ApiResponse<T>`:

```java
public record ApiResponse<T>(
    boolean success,
    String message,
    T data,
    Object meta,
    Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) { ... }
    public static <T> ApiResponse<T> ok(T data, String message) { ... }
    public static <T> ApiResponse<T> created(T data) { ... }
    public static ApiResponse<Void> noContent() { ... }
    public static ApiResponse<Void> error(String message) { ... }
}
```

HTTP status codes match the factory method:

| Factory | HTTP Status |
|---------|-------------|
| `ok(data)` | 200 |
| `created(data)` | 201 |
| `noContent()` | 204 |
| (exception) | 400 / 401 / 403 / 404 / 409 / 500 |

### Pagination

Request: `GET /api/v1/journal?page=0&size=20`

Response:

```java
Page<JournalResponse> page = service.list(userId, PageRequest.of(p, size, sort));
return ApiResponse.ok(page.getContent(), PageMeta.of(page));
```

`PageMeta` carries: `page`, `size`, `totalElements`, `totalPages`, `last`.

### Request Validation

Use Bean Validation on all DTOs:

```java
public record CreateJournalRequest(
    @NotBlank(message = "Content is required")
    @Size(max = 5000, message = "Content must not exceed 5000 characters")
    String content,

    @NotNull(message = "Mood is required")
    MoodLevel mood
) {}
```

Controller: `@Valid @RequestBody CreateJournalRequest req`

### OpenAPI Annotations

```java
@Tag(name = "Journal", description = "Journal entry management")
@RestController
@RequestMapping("/api/v1/journal")
public class JournalController {

    @Operation(summary = "Create a new journal entry")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JournalResponse> create(...) { ... }
}
```

---

## Exception Handling

### Exception Hierarchy

```
RuntimeException
└── AppException
    └── BadRequestException
```

`AppException` carries `HttpStatus`, an error code string, a message, and optional detail payload:

```java
public class AppException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Object details;

    public static AppException notFound(String message) {
        return new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, null);
    }
    public static AppException badRequest(String message) { ... }
    public static AppException conflict(String message) { ... }
    public static AppException forbidden(String message) { ... }
    public static AppException unauthorized(String message) { ... }
}
```

### Global Handler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleApp(AppException ex) {
        return ResponseEntity.status(ex.getStatus())
            .body(ErrorResponse.of(ex.getCode(), ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = ex.getBindingResult().getFieldErrors().stream()
            .collect(toMap(FieldError::getField, FieldError::getDefaultMessage));
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of("VALIDATION_FAILED", "Validation error", details));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
            .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", null));
    }
}
```

`ErrorResponse` record: `success=false`, `code`, `message`, `details`, `timestamp`.

### Rules

- Throw `AppException` static factories from service layer — never return `null` to signal failure.
- Never catch-and-swallow exceptions in service methods.
- Never log `ex.getMessage()` for generic exceptions — log the full stack trace.
- Never expose internal stack traces in API responses.

---

## Logging

### Setup

Use Lombok `@Slf4j` on every class that needs logging:

```java
@Slf4j
@Service
@RequiredArgsConstructor
class AuthServiceImpl implements AuthService {
    ...
}
```

### Log Levels

| Level | Use for |
|-------|---------|
| `ERROR` | Unhandled exceptions (always include stack trace) |
| `WARN` | Security events, suspicious input, admin access, validation failures |
| `INFO` | Business events: user registered, login, resource created/updated/deleted |
| `DEBUG` | Internal state useful during development (disabled in production) |

### application.yml defaults

```yaml
logging:
  level:
    root: INFO
    com.<org>.<app>: DEBUG
    org.springframework.security: WARN
    org.hibernate.SQL: WARN
```

### Sensitive Data Rules

- **Email/username**: log as `[REDACTED]` in security contexts.
- **Passwords**: never log, even hashed forms.
- **JWT tokens**: never log raw token; log only the exception class on validation failure.
- **User content** (journal entries, messages): never log; add inline comment `// sensitive — do not log`.

```java
// Good
log.info("Login attempt for email=[REDACTED]");
log.warn("JWT validation failed: {}", ex.getClass().getSimpleName());

// Bad
log.info("Login attempt for email={}", email);
log.warn("JWT error: {}", ex.getMessage()); // may include token fragment
```

---

## Security

### JWT Configuration

- Algorithm: HMAC-SHA256 (HS256).
- Secret from environment variable (never hardcoded).
- Claims: `sub` = userId, plus `email` and `role`.
- Short-lived access token + long-lived refresh token.

### Refresh Token Storage

- Store only the **SHA-256 hash** of the refresh token in the database.
- On refresh: validate hash, issue new pair, revoke old token (set `revokedAt = now()`).
- `isValid()` = `revokedAt == null && !isExpired()`.

### JWT Filter

Extend `OncePerRequestFilter`. Extract `Authorization: Bearer <token>`, validate, then set `SecurityContextHolder`:

```java
UsernamePasswordAuthenticationToken auth =
    new UsernamePasswordAuthenticationToken(principal, null, authorities);
SecurityContextHolder.getContext().setAuthentication(auth);
```

### Authorization Rules

Method-level with `@PreAuthorize`:

```java
// Admin only
@PreAuthorize("hasRole('ADMIN')")

// Self or admin
@PreAuthorize("hasRole('ADMIN') or (#userId != null and authentication.principal.toString() == #userId.toString())")
```

In service: always verify ownership with combined queries:

```java
JournalEntry entry = journalRepo.findByIdAndUserId(id, userId)
    .orElseThrow(() -> AppException.notFound("Entry not found"));
```

### Rate Limiting

Custom `OncePerRequestFilter` using a sliding-window counter per IP. Applies to public auth endpoints:

```java
private static final int MAX_REQUESTS = 10;
private static final long WINDOW_MS = 60_000;
```

Resolve IP from `X-Forwarded-For` for proxy support. Respond with `429 TOO_MANY_REQUESTS` on breach.

### CORS

Define `CorsConfigurationSource` bean in `SecurityConfig`. Load allowed origins from config (support multiple, e.g. localhost dev + production URL). Always include:

```java
config.setAllowCredentials(true);
config.setMaxAge(3600L);
```

### Passwords

- `BCryptPasswordEncoder` with strength 12.
- Never store plaintext or reversible hashes.
- Separate "security password" for sensitive in-app operations (stored as its own hash column).

---

## Database & Persistence

### Flyway Migrations

- All schema changes via Flyway scripts in `src/main/resources/db/migration/`.
- Naming: `V<version>__<description>.sql` (e.g., `V1__initial_schema.sql`).
- Never modify an already-applied migration script.

### Repository Conventions

```java
public interface JournalEntryRepository
    extends JpaRepository<JournalEntry, Long>, JpaSpecificationExecutor<JournalEntry> {

    Optional<JournalEntry> findByIdAndUserId(Long id, Long userId);
    boolean existsByUserIdAndEntryAt(Long userId, LocalDate entryAt);
}
```

- Use derived query methods for simple lookups.
- Use Specifications for dynamic, multi-condition queries.
- Use `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@Query` only for concurrent upsert scenarios.
- Never write `@Query` JPQL/SQL where a Specification or derived method suffices.

### Transaction Rules

```java
@Service
@Transactional          // default: read-write for the class
class JournalServiceImpl implements JournalService {

    @Transactional(readOnly = true)   // override for queries
    public Page<JournalResponse> list(Long userId, Pageable pageable) { ... }

    // write methods inherit class-level @Transactional
    public JournalResponse create(Long userId, CreateJournalRequest req) { ... }
}
```

- Mark query methods `readOnly = true` — Hibernate and the DB can optimize.
- Publish application events **inside** the transaction to guarantee consistency.
- Never start a new transaction in a repository method; let the service own transaction boundaries.

### Pagination Standard

```java
PageRequest.of(page, size, Sort.by("createdAt").descending())
```

Default: `size = 20`, `sort = createdAt DESC`.

---

## Configuration Management

### Source Priority (highest → lowest)

1. Environment variables
2. `.env` file (referenced via `spring.config.import`)
3. `application.yml`
4. Default values in `@ConfigurationProperties` classes

### application.yml Layout

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  flyway:
    enabled: true
    locations: classpath:db/migration

app:
  jwt:
    secret: ${JWT_SECRET}
    access-token-expiry-ms: 900000        # 15 min
    refresh-token-expiry-ms: 2592000000   # 30 days
  cors:
    allowed-origins:
      - http://localhost:3000
      - https://your-app.vercel.app
  admin:
    email: ${ADMIN_EMAIL}
    password: ${ADMIN_PASSWORD}
```

### Startup Initialization

Use `CommandLineRunner` (not `@PostConstruct`) to seed required data (roles, default admin):

```java
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    @Override
    @Transactional
    public void run(String... args) {
        seedRoles();
        seedAdminUser();
    }
}
```

---

## Testing

### Test Structure

```
src/test/java/com/<org>/<app>/
├── common/
│   └── BaseIntegrationTest.java   # shared @SpringBootTest + TestContainers setup
└── module/
    └── <domain>/
        ├── <Domain>ServiceTest.java       # unit test (Mockito)
        └── <Domain>ControllerTest.java    # integration test (MockMvc)
```

### Integration Tests

Use TestContainers to spin up a real database container:

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public abstract class BaseIntegrationTest {

    @Container
    static MSSQLServerContainer<?> mssql =
        new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mssql::getJdbcUrl);
        registry.add("spring.datasource.username", mssql::getUsername);
        registry.add("spring.datasource.password", mssql::getPassword);
    }
}
```

### Unit Tests

Mock repositories and collaborators with Mockito. Never mock the database layer in integration tests.

### Naming

| Test method | Pattern |
|-------------|---------|
| `<method>_<scenario>_<expectedOutcome>` | `create_duplicateEmail_throwsConflict()` |
| Integration/E2E | `<endpoint>Returns<Status>` | `loginEndpointReturnsOkWithToken()` |

---

## Sensitive Data Handling

| Data | Rule |
|------|------|
| Passwords | BCrypt hash only; never log, never return in responses |
| JWT tokens | Never log raw; log only exception class names |
| Refresh tokens | Store SHA-256 hash; issue raw token once in response only |
| Email in logs | Log as `[REDACTED]` in security/auth contexts |
| User-generated content | Add `// sensitive — do not log` comment on entity fields |
| Database credentials | Environment variable only; never in source control |
| JWT secret | Environment variable only; minimum 256-bit entropy |

### Response DTO Hygiene

Never expose entity fields that were not intentionally included in the response DTO. Review every response class for leaked sensitive fields (passwordHash, deletedAt, internalFlags).

---

*This guide reflects conventions established in the `mind-recharge-be` project (Spring Boot 4.x, Java 21, MSSQL). Adjust stack versions and DB dialect as needed for new projects.*