# Notification Feature — Giải thích chi tiết

> File này giải thích **tư duy thiết kế**, **vai trò từng đoạn code** và **luồng chạy** của tính năng Thông báo trong ChipTrip. Mục tiêu: đọc xong bạn hiểu *tại sao* tôi viết như thế, không chỉ *cái gì*.

---

## Mục lục

1. [Vấn đề cần giải quyết](#1-vấn-đề-cần-giải-quyết)
2. [Các phương án và lý do chọn hybrid REST + WebSocket](#2-các-phương-án-và-lý-do-chọn-hybrid-rest--websocket)
3. [Sơ đồ kiến trúc tổng thể](#3-sơ-đồ-kiến-trúc-tổng-thể)
4. [Domain model — Entity Notification](#4-domain-model--entity-notification)
5. [Repository — query gì và vì sao](#5-repository--query-gì-và-vì-sao)
6. [Service + DTO — anti-IDOR, transactional](#6-service--dto--anti-idor-transactional)
7. [REST Controller — 4 endpoint](#7-rest-controller--4-endpoint)
8. [Event-driven: vì sao KHÔNG gọi NotificationService trực tiếp](#8-event-driven-vì-sao-không-gọi-notificationservice-trực-tiếp)
9. [`@TransactionalEventListener(AFTER_COMMIT)` — chi tiết](#9-transactionaleventlisteneraftercommit--chi-tiết)
10. [Event Listener — 2 bước save DB → push WS](#10-event-listener--2-bước-save-db--push-ws)
11. [WebSocket STOMP + JWT auth](#11-websocket-stomp--jwt-auth)
12. [Scheduler — TripReminder + WeatherAlert](#12-scheduler--tripreminder--weatheralert)
13. [Frontend — REST + WS hook](#13-frontend--rest--ws-hook)
14. [Luồng end-to-end cho từng loại notification](#14-luồng-end-to-end-cho-từng-loại-notification)
15. [Câu hỏi để học sâu hơn](#15-câu-hỏi-để-học-sâu-hơn)

---

## 1. Vấn đề cần giải quyết

Khi user A thêm user B vào chuyến đi, user B cần thấy thông báo. Cụ thể:

- **Khi B đang online** → thấy ngay (toast/popup) không cần refresh.
- **Khi B offline** → lần mở app sau vẫn thấy.
- **Không bị mất noti** dù server restart, mạng chập chờn, hay B chuyển thiết bị.

4 loại trong MVP:
| Loại | Khi nào | Người nhận |
|---|---|---|
| `TRIP_MEMBER_ADDED` | Owner thêm thành viên | Thành viên được thêm |
| `TRIP_REMINDER` | 8h sáng hôm trước ngày khởi hành | Owner chuyến đi |
| `AI_CREDITS_LOW` | Sau generate, credits còn ≤ 1 | Chính user generate |
| `WEATHER_ALERT` | 7h sáng, có mưa/bão trong các ngày chuyến đi (5 ngày tới) | Owner chuyến đi |

---

## 2. Các phương án và lý do chọn hybrid REST + WebSocket

### Phương án A: Chỉ REST polling
- FE poll `/notifications/unread-count` mỗi 30s.
- Đơn giản, không cần WS.
- **Nhược điểm**: latency tệ (max 30s delay), tốn request rỗng, không "realtime feel".

### Phương án B: Chỉ WebSocket
- Push qua WS mọi thông báo.
- **Nhược điểm**: nếu B offline lúc push → mất noti. Không persist. Không có "đọc lại lịch sử".

### Phương án C — đã chọn: REST + WebSocket
- **REST là source of truth**: mọi noti được lưu DB. List/count luôn query DB.
- **WebSocket là listener phụ**: đẩy realtime *khi user online*. Nếu push lỗi, không sao — DB đã có.

→ Best of both worlds: realtime khi online, vẫn nhận đầy đủ khi offline.

> **Bài học**: WebSocket không phải để *thay thế* REST mà để *bổ sung*. Source of truth luôn là DB.

---

## 3. Sơ đồ kiến trúc tổng thể

```
┌─────────────────────────────────────────────────────────────────┐
│                          BACKEND                                │
│                                                                  │
│  TripMemberService.addMember()  (business logic)                │
│           │                                                      │
│           │ publishEvent(TripMemberAddedEvent)                  │
│           ▼                                                      │
│   ApplicationEventPublisher (Spring built-in)                   │
│           │                                                      │
│           │ AFTER_COMMIT (chỉ chạy khi transaction commit OK)   │
│           ▼                                                      │
│   NotificationEventListener.onTripMemberAdded()                 │
│           │                                                      │
│           ├─[1]──► NotificationService.create()                 │
│           │            │                                         │
│           │            ▼                                         │
│           │      INSERT notifications (luôn chạy)               │
│           │                                                      │
│           └─[2]──► SimpMessagingTemplate.convertAndSendToUser() │
│                        │                                         │
│                        ▼                                         │
│                  /user/{userId}/queue/notifications              │
│                        │                                         │
└────────────────────────┼─────────────────────────────────────────┘
                         │   WebSocket STOMP (SockJS)
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                         FRONTEND                                 │
│                                                                  │
│  useNotificationSocket() subscribe /user/queue/notifications    │
│           │                                                      │
│           ├─ Toast popup                                         │
│           └─ qc.invalidateQueries → REST refetch list + count   │
│                                                                  │
│  NotificationBell → unread badge + dropdown                     │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Domain model — Entity Notification

```java
@Entity
@Table(name = "notifications",
    indexes = @Index(name = "ix_notifications_user_unread_created",
                     columnList = "user_id, is_read, created_at"))
public class Notification extends BaseAuditEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    private NotificationType type;

    private String title;
    private String body;
    private Long refId;     // polymorphic — tripId hoặc null
    private boolean isRead;
}
```

### Quyết định thiết kế

**1. `recipient` ManyToOne với `FetchType.LAZY`**
- Notification thuộc về 1 user. ManyToOne là chuẩn.
- LAZY vì khi list 20 noti, ta không muốn load 20 User object đi kèm (N+1 query). Chỉ load khi thật cần.

**2. `type` lưu STRING không lưu ordinal**
```java
@Enumerated(EnumType.STRING)
```
- `EnumType.ORDINAL` lưu 0,1,2,3 → khi thêm enum value mới vào giữa, dữ liệu cũ bị sai.
- STRING lưu tên enum → an toàn khi mở rộng.

**3. `refId` thay vì FK cứng**
- Nếu là FK `trip_id` thì khi mở rộng loại noti (vd `EXPENSE_ADDED` ref expense), phải thêm cột mới.
- `refId` polymorphic: cùng cột Long, mỗi type tự diễn giải. Đơn giản, đủ cho MVP.
- Nhược điểm: không có ràng buộc DB. Trade-off: đổi lấy linh hoạt.

**4. Index `(user_id, is_read, created_at)`**
- Query phổ biến nhất: "noti chưa đọc của user X sắp xếp theo thời gian".
- Composite index theo thứ tự selectivity: `user_id` lọc trước (1 user có vài chục noti), `is_read` lọc tiếp, `created_at` để sort.
- Không cần index riêng cho `created_at` — DB dùng được index này cho sort luôn.

**5. Extends `BaseAuditEntity`**
- Tự động có `id`, `createdAt`, `updatedAt` (từ `@CreatedDate` + `@LastModifiedDate` của Spring Data JPA Auditing).
- Đỡ phải viết lại boilerplate.

---

## 5. Repository — query gì và vì sao

```java
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByRecipientIdAndIsReadFalse(Long userId);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.recipient.id = :userId AND n.isRead = false")
    int markAllReadByRecipient(@Param("userId") Long userId);

    boolean existsByRecipientIdAndTypeAndRefIdAndBodyContaining(
        Long userId, NotificationType type, Long refId, String bodyFragment);
}
```

### Vì sao 4 method này, không nhiều/ít hơn

**`findByRecipientIdOrderByCreatedAtDesc` (derived query)**
- Spring Data JPA tự sinh SQL từ tên method — không cần `@Query`.
- `Page<Notification>` thay vì `List`: free pagination + total count cho FE.
- `OrderByCreatedAtDesc` — mới nhất trước (đúng UX feed thông báo).

**`countByRecipientIdAndIsReadFalse`**
- Cho badge "unread count" — chỉ cần số, không cần data.
- `SELECT COUNT(*)` nhanh hơn `SELECT * + count` nhiều lần khi có index.

**`markAllReadByRecipient` với `@Modifying`**
- Một query UPDATE đánh dấu tất cả. Hiệu quả hơn load từng entity rồi save.
- `@Modifying` bắt buộc cho DML — không có thì Hibernate báo lỗi.
- Trả `int` = số row update để log.

**`existsByRecipientIdAndTypeAndRefIdAndBodyContaining`**
- Dùng cho WEATHER_ALERT dedup: "đã alert trip X ngày Y chưa?". Body chứa ISO date của ngày, lookup nhanh.
- `exists` thay vì `findOne` — chỉ cần boolean, DB optimize `SELECT 1 LIMIT 1`.

> **Bài học**: Tên method = SQL. Đừng viết `@Query` khi derived query đủ dùng.

---

## 6. Service + DTO — anti-IDOR, transactional

### Service interface + impl package-private

```java
public interface NotificationService { ... }     // public

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class NotificationServiceImpl implements NotificationService {  // package-private
    ...
}
```

**Vì sao interface + impl?**
- Test dễ: mock interface trong unit test.
- Caller chỉ thấy interface, không thấy implementation → khó vô tình couple.
- `class` package-private (không có `public`) — chỉ cùng package mới truy cập. Test cùng package vẫn OK.

### Anti-IDOR: bảo vệ `markRead`

```java
@Override
@Transactional
public void markRead(Long userId, Long notificationId) {
    Notification n = notificationRepository.findById(notificationId)
            .orElseThrow(() -> AppException.notFound("Không tìm thấy thông báo"));

    // Anti-IDOR: notification phải thuộc về user. Trả 404 để KHÔNG leak existence.
    if (!n.getRecipient().getId().equals(userId)) {
        throw AppException.notFound("Không tìm thấy thông báo");
    }
    ...
}
```

**IDOR = Insecure Direct Object Reference**: user 99 đoán id `5` rồi gọi `PATCH /notifications/5/read` — nếu BE không kiểm tra ownership, user 99 có thể đánh dấu noti của user 7 đã đọc.

**Sửa**:
1. Load noti theo id.
2. Kiểm tra `recipient.id == userId` (user trong JWT).
3. Nếu không khớp → trả **404** (không phải 403). 404 không leak thông tin: attacker không biết noti có tồn tại hay không.

> **Bài học**: Mỗi endpoint thao tác trên id phải tự kiểm tra ownership. Đừng tin client.

### DTO record

```java
public record NotificationDto(Long id, NotificationType type, String title, ...) {
    public static NotificationDto from(Notification n) { ... }
}
```

**Vì sao record?**
- Immutable, tự sinh `equals`/`hashCode`/`toString`.
- Tự sinh getter (`dto.id()` thay vì `dto.getId()`).
- 1 dòng thay vì 50 dòng boilerplate.

**Vì sao tách DTO?**
- KHÔNG trả `Notification` entity ra API: leak field nội bộ (`updatedAt`, lazy proxy `recipient`...), serialize lazy có thể gây `LazyInitializationException`.
- Có DTO → contract API ổn định khi entity đổi.

---

## 7. REST Controller — 4 endpoint

```java
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    @GetMapping
    public ApiResponse<List<NotificationDto>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<NotificationDto> result = notificationService.list(principal.getId(), pageable);
        return ApiResponse.ok(result.getContent(), PageMeta.of(result));
    }
    ...
}
```

### Các quyết định nhỏ nhưng quan trọng

**1. `@AuthenticationPrincipal UserPrincipal principal`**
- Lấy user hiện tại từ JWT đã verify trong `JwtAuthFilter`.
- KHÔNG nhận `userId` từ path/query → tránh IDOR (user không thể giả mạo id user khác).

**2. Validate `page`, `size` ngay tại controller**
```java
Math.max(0, page)              // ko cho âm
Math.min(Math.max(1, size), 100)  // size phải >= 1, <= 100 (chống tải nặng)
```
- Validate ở rìa hệ thống. Nếu để service xử lý, lỗ hổng dễ thoát.

**3. Trả `ApiResponse<T>` wrapper**
- Đồng nhất với rest of API. FE chỉ cần unwrap `data` + `meta` 1 chỗ.

**4. Sử dụng `@PatchMapping` cho mark-read**
- HTTP verb đúng nghĩa: `PATCH` = partial update (chỉ đổi field `isRead`).
- Không dùng `POST /mark-read` (POST = create new resource).

---

## 8. Event-driven: vì sao KHÔNG gọi NotificationService trực tiếp

### Cách SAI (anti-pattern)

```java
// TripMemberServiceImpl
public TripMemberResponse addMember(...) {
    ...
    tripMemberRepository.save(member);
    notificationService.create(linkedUser.getId(), TRIP_MEMBER_ADDED, ...);  // ❌
    messagingTemplate.convertAndSendToUser(...);  // ❌
    return ...;
}
```

### Vấn đề

1. **Tight coupling**: `TripMemberService` biết về `NotificationService` và `SimpMessagingTemplate`. Sau này thêm 1 loại noti nữa → phải sửa code addMember.
2. **Mở rộng khó**: thêm chức năng "send email khi thêm member" → lại sửa addMember.
3. **Test khó**: test addMember phải mock cả notification + messaging.
4. **Vi phạm SRP** (Single Responsibility Principle): addMember làm 2 việc — thêm member + thông báo.
5. **Transaction risk**: nếu insert noti fail (DB constraint, network), rollback luôn việc add member → mất add member do lỗi noti? Không ổn.

### Cách ĐÚNG: publish event

```java
public TripMemberResponse addMember(...) {
    ...
    member = tripMemberRepository.save(member);

    if (linkedUser != null) {
        eventPublisher.publishEvent(new TripMemberAddedEvent(
                linkedUser.getId(), trip.getId(), trip.getTitle(), inviterName));
    }
    return toResponse(member);
}
```

- `addMember` chỉ làm 1 việc: thêm member. Phát event "đã thêm member" rồi xong.
- Các side effect (noti, email, analytics, achievement…) là **listener** riêng, nghe event và phản ứng.
- Thêm loại side effect mới → thêm listener mới. KHÔNG sửa addMember.

> **Bài học**: Event-driven là cách *đảo ngược dependency*. Domain service không biết ai nghe, ai sẽ nghe đăng ký riêng. Giống pub-sub.

### `record` cho event

```java
public record TripMemberAddedEvent(
        Long recipientUserId,
        Long tripId,
        String tripTitle,
        String inviterName
) {}
```

- Immutable.
- Chỉ chứa data tối thiểu cần để xử lý — không truyền entity Hibernate vì có thể detached, lazy issue, sang thread khác (async listener) hỏng.

---

## 9. `@TransactionalEventListener(AFTER_COMMIT)` — chi tiết

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onTripMemberAdded(TripMemberAddedEvent e) { ... }
```

### Vấn đề nếu dùng `@EventListener` thường

```java
@Transactional
public void addMember(...) {
    member = tripMemberRepository.save(member);
    publishEvent(...);  // listener chạy ngay, TRƯỚC khi transaction commit

    // Giả sử dòng dưới throw exception
    if (somethingBad) throw new RuntimeException();
    // → Transaction rollback. Member KHÔNG được lưu DB.
    // Nhưng noti ĐÃ tạo (cũng rollback?) và WS ĐÃ push!
}
```

`@EventListener` chạy **đồng bộ trong transaction**. Nếu transaction rollback, noti DB cũng rollback (vì cùng transaction)... nhưng WS push đã đi rồi — không thể "un-send".

→ User nhận noti "Bạn được thêm vào trip X" nhưng thực ra việc add đã bị rollback. **Sai sự thật**.

### Giải pháp: `AFTER_COMMIT`

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
```

- Listener chỉ chạy **sau khi transaction commit thành công**.
- Nếu transaction rollback → listener KHÔNG chạy → không tạo noti, không push WS.
- Đảm bảo: noti chỉ tồn tại khi action thực sự xảy ra.

### Khi nào dùng `@EventListener` thường thay vì `@TransactionalEventListener`?

Trong code của tôi:
```java
@EventListener
public void onTripReminder(TripReminderEvent e) { ... }

@EventListener
public void onWeatherAlert(WeatherAlertEvent e) { ... }
```

→ 2 event này phát ra từ **scheduler**, không nằm trong transaction nghiệp vụ nào. Không có gì để rollback. Dùng `@EventListener` thường được.

> **Bài học**: Listener chạy trong/ngoài transaction là 2 cảnh hoàn toàn khác. Hãy chọn đúng phase.

---

## 10. Event Listener — 2 bước save DB → push WS

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onTripMemberAdded(TripMemberAddedEvent e) {
    String title = "Bạn được mời vào chuyến đi";
    String body = String.format("%s đã thêm bạn vào '%s'", e.inviterName(), e.tripTitle());

    // [1] LUÔN LUÔN: save DB
    Notification n = notificationService.create(
            e.recipientUserId(), NotificationType.TRIP_MEMBER_ADDED, title, body, e.tripId());

    // [2] THỬ: push WS
    push(e.recipientUserId(), n);
}

private void push(Long userId, Notification n) {
    try {
        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId), "/queue/notifications", NotificationDto.from(n));
    } catch (MessagingException ex) {
        // KHÔNG được làm fail luồng nghiệp vụ. Chỉ log warn.
        log.warn("Failed to push notification {} via WS to userId={}: {}",
                n.getId(), userId, ex.getMessage());
    }
}
```

### Vì sao thứ tự **save DB trước, push WS sau**?

- Nếu push WS trước, save sau:
  - User nhận message qua WS (toast)
  - Save DB fail → user click vào toast → DB không có noti này → 404
  - Mâu thuẫn UX.

- Save trước, push sau:
  - DB có noti trước. Nếu push WS lỗi, user offline thì sao? → Khi mở app, REST load list, vẫn thấy. Không mất gì.
  - WS chỉ là *tăng tốc* cho user online.

### Vì sao bọc try/catch quanh `convertAndSendToUser`?

- WebSocket broker có thể tạm thời lỗi (mạng, broker restart).
- KHÔNG được làm exception ném ra → ảnh hưởng listener khác / transaction nghiệp vụ.
- Try/catch hẹp **chỉ quanh chỗ có thể fail** + log warn + tiếp tục.

> **Bài học**: Side effect best-effort (WS, email, analytics) không bao giờ được làm fail core logic. Save DB là core; push là phụ.

---

## 11. WebSocket STOMP + JWT auth

### Vì sao SockJS + STOMP, không phải native WebSocket?

| | Native WS | STOMP (qua SockJS) |
|---|---|---|
| Subscribe theo channel | Tự code | Có sẵn (`/topic`, `/queue`, `/user`) |
| Reconnect | Tự code | STOMP client tự xử lý |
| Heartbeat | Tự code | Built-in |
| Fallback HTTP long-polling | Không | SockJS có (qua proxy chặn WS) |
| Server push tới user cụ thể | Tự manage map `userId → session` | `convertAndSendToUser` built-in |

→ Với scope MVP, STOMP tiết kiệm rất nhiều code custom.

### Config

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(/* origins từ config */)
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");     // server → client
        registry.setApplicationDestinationPrefixes("/app");  // client → server
        registry.setUserDestinationPrefix("/user");          // user-specific
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtChannelInterceptor);
    }
}
```

### Convention destination

- **`/topic/xxx`**: broadcast (mọi subscriber nhận). Vd `/topic/trip-updates` cho tất cả ai theo dõi.
- **`/queue/xxx`**: point-to-point. Mỗi message tới 1 client.
- **`/user/{userId}/queue/xxx`**: queue gắn với user cụ thể. Spring tự route.

→ Notification của 1 user là **point-to-point** + **user-specific** → dùng `/user/queue/notifications`.

### Push từ server

```java
messagingTemplate.convertAndSendToUser("7", "/queue/notifications", dto);
```

→ Tới đích `/user/7/queue/notifications`. Client subscribe `/user/queue/notifications` (Spring tự thêm `/user/{principal.name}` trước khi match).

### JWT auth — chỗ tricky nhất

#### Vấn đề: WebSocket handshake là HTTP, nhưng STOMP message thì không

- HTTP handshake bị `SecurityFilterChain` filter — nếu yêu cầu auth qua header, browser KHÔNG gửi `Authorization` được trong WS handshake (chỉ subprotocol, no custom header).
- Cách workaround: gửi token qua **STOMP CONNECT frame** (đây là frame STOMP layer, không phải HTTP).

#### Code

```java
@Component
public class JwtChannelInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        // CHỈ check ở CONNECT — không phải mọi frame (sẽ tốn quá nhiều)
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw AppException.unauthorized("Thiếu Authorization header");
            }
            String token = authHeader.substring(7);

            Long userId = jwtProvider.getUserId(token);
            String email = jwtProvider.getEmail(token);

            UserPrincipal principal = (UserPrincipal) userDetailsService.loadUserByUsername(email);

            // 🔑 KEY POINT: Principal.getName() = String.valueOf(userId)
            //    Vì convertAndSendToUser("7", ...) route tới user có name="7"
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    new WsUserPrincipal(principal.getId()), null, principal.getAuthorities());
            accessor.setUser(auth);
        }
        return message;
    }

    private record WsUserPrincipal(Long id) implements java.security.Principal {
        @Override public String getName() { return String.valueOf(id); }
    }
}
```

### Tại sao phải wrap `Principal` để `getName()` = userId?

Spring routing logic:
```
convertAndSendToUser("7", "/queue/notifications", dto)
    ↓ internal lookup
session whose Principal.getName() == "7"
    ↓
gửi tới session đó
```

- `UserPrincipal` mặc định trả `email` ở `getUsername()` → nếu để default, routing dùng email làm key.
- Backend `convertAndSendToUser(String.valueOf(userId), ...)` đang dùng userId làm key.
- → MISMATCH: message gửi tới "7" không tới được session có `Principal.getName()="user@a.com"`.

**Fix**: tạo `WsUserPrincipal` record với `getName()` trả `String.valueOf(id)`. Bây giờ:
- Server: gửi tới "7" → tìm session có `Principal.getName()=="7"` → match.
- Client: connect, server gắn `WsUserPrincipal(7)` vào session.

→ Hoạt động.

### SecurityConfig

```java
.requestMatchers("/ws/**").permitAll()
```

- WS handshake (HTTP upgrade) phải pass qua filter chain. Đặt permit để filter không reject.
- Auth thực hiện ở **STOMP CONNECT frame** qua `JwtChannelInterceptor`, không phải HTTP filter.

> **Bài học**: WebSocket có 2 lớp auth tiềm năng: HTTP handshake và STOMP CONNECT. Vì browser không gửi custom header HTTP qua WS, dùng STOMP CONNECT là chuẩn.

---

## 12. Scheduler — TripReminder + WeatherAlert

### Setup

```java
@Configuration
@EnableScheduling
public class SchedulingConfig {}
```

→ Bật toàn cục cơ chế `@Scheduled`.

### TripReminder

```java
@Component
public class TripReminderScheduler {

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Ho_Chi_Minh")
    public void sendTripReminders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        List<Trip> trips = tripRepository.findByDateStart(tomorrow);
        for (Trip trip : trips) {
            eventPublisher.publishEvent(new TripReminderEvent(
                    trip.getUser().getId(), trip.getId(), trip.getTitle(), trip.getDateStart()));
        }
    }
}
```

**Decode cron**: `"0 0 8 * * *"` = giây 0, phút 0, giờ 8, mọi ngày tháng năm. → 8:00:00 sáng mỗi ngày.

**`zone = "Asia/Ho_Chi_Minh"`** — quan trọng. Nếu không set, dùng default JVM timezone, server cloud thường UTC → 8h UTC = 15h VN, sai giờ user.

**Publish event thay vì gọi service trực tiếp** — cùng lý do mục 8. Listener đã handle cả việc save + push.

### WeatherAlert (dedup)

```java
@Scheduled(cron = "0 0 7 * * *", zone = "Asia/Ho_Chi_Minh")
public void scanForWeatherAlerts() {
    LocalDate today = LocalDate.now();
    LocalDate horizon = today.plusDays(FORECAST_HORIZON_DAYS);
    List<Trip> upcoming = tripRepository.findByDateStartBetween(today, horizon);

    for (Trip trip : upcoming) {
        WeatherResponse weather = externalApiService.getWeather(
                trip.getDestination(), trip.getDateStart(), trip.getDateEnd());

        for (WeatherResponse.DayForecast f : weather.getForecasts()) {
            if (!BAD_CONDITIONS.contains(f.getCondition())) continue;
            if (f.getDate().isBefore(trip.getDateStart()) || f.getDate().isAfter(trip.getDateEnd())) continue;

            // Dedup
            boolean exists = notificationRepository.existsByRecipientIdAndTypeAndRefIdAndBodyContaining(
                    userId, NotificationType.WEATHER_ALERT, trip.getId(), f.getDate().toString());
            if (exists) continue;

            eventPublisher.publishEvent(new WeatherAlertEvent(...));
        }
    }
}
```

**Tại sao cần dedup?**
- Scheduler chạy mỗi ngày. Nếu trip có 5 ngày mưa, không dedup thì mỗi sáng tạo 5 noti mới → 25 noti trong 5 ngày.
- Dedup: kiểm tra "đã alert trip X cho ngày Y chưa". Body chứa ISO date làm marker.

**Cảnh báo về scaling:**
```java
// Lưu ý vận hành: khi scale nhiều instance, @Scheduled chạy trên mọi instance
// gây thông báo trùng → cần ShedLock. MVP 1 instance chưa cần.
```

- `@Scheduled` chạy local mỗi instance. Có 3 server → 8h sáng cả 3 cùng chạy → 3 noti trùng.
- ShedLock (library) dùng DB lock để chỉ 1 instance được chạy. Đến lúc scale mới cần.

---

## 13. Frontend — REST + WS hook

### Kiến trúc

```
┌────────────────────────────────────────────┐
│ NotificationBell (UI)                      │
│   ├─ useUnreadCount()  → badge             │
│   ├─ useNotificationList()  → dropdown     │
│   ├─ useMarkRead() / useMarkAllRead()      │
│   └─ useNotificationSocket()  → toast + invalidate
└────────────────────────────────────────────┘
        ↓ axios               ↓ STOMP
    /api/v1/notifications  /ws + /user/queue/notifications
```

### STOMP client wrapper

```typescript
export function connectNotificationSocket(token, onNotification, options) {
    const client = new Client({
        webSocketFactory: () => new SockJS(WS_ENDPOINT) as unknown as WebSocket,
        connectHeaders: { Authorization: `Bearer ${token}` },
        reconnectDelay: 5_000,
        heartbeatIncoming: 10_000,
        heartbeatOutgoing: 10_000,
    });

    client.onConnect = () => {
        client.subscribe("/user/queue/notifications", (msg) => {
            const dto = JSON.parse(msg.body);
            onNotification(dto);
        });
    };

    client.activate();
    return { disconnect: () => client.deactivate(), ... };
}
```

**Quyết định:**
- `reconnectDelay: 5_000` — mất mạng → tự retry mỗi 5s.
- `heartbeat` 10s — phát hiện connection chết.
- `Bearer token` trong `connectHeaders` → server thấy ở `accessor.getFirstNativeHeader("Authorization")` ở CONNECT frame.
- Subscribe `/user/queue/notifications` (KHÔNG có userId trong path) — Spring tự prefix với principal.name.

### Hook tích hợp với React Query

```typescript
export function useNotificationSocket(onMessage?) {
    const { user } = useAuth();
    const qc = useQueryClient();
    const handleRef = useRef(null);

    useEffect(() => {
        if (!user) return;

        const connect = () => {
            const token = authStorage.getAccessToken();
            handleRef.current?.disconnect();
            handleRef.current = connectNotificationSocket(token, (n) => {
                // Khi nhận message → invalidate, React Query refetch
                qc.invalidateQueries({ queryKey: notificationKeys.list });
                qc.invalidateQueries({ queryKey: notificationKeys.unread });
                onMessage?.(n);
            });
        };

        connect();

        // Reconnect khi auth thay đổi (token refresh, đổi tài khoản)
        const onAuthChange = () => connect();
        window.addEventListener("chiptrip-auth-change", onAuthChange);

        return () => {
            window.removeEventListener("chiptrip-auth-change", onAuthChange);
            handleRef.current?.disconnect();
        };
    }, [user?.id]);
}
```

**Tinh tế:**
- `useRef` giữ handle qua re-render mà không trigger re-render.
- `cbRef` giữ callback mới nhất (vì closure trong subscribe đóng băng callback đầu tiên).
- Cleanup function disconnect khi unmount/dep change → tránh leak connection.
- Lắng nghe event `chiptrip-auth-change` → khi user refresh token / login lại → reconnect với token mới.

### Vì sao kết hợp WS + React Query?

- WS push 1 message → KHÔNG tự thêm vào list trong cache.
- Cách 1: tự `qc.setQueryData(...)` thêm vào head list. Phức tạp (sort, dedup, pagination).
- Cách 2 (dùng ở đây): `invalidate` → React Query refetch list từ server. Đơn giản, đúng đắn (vì REST là source of truth).

Trade-off: 1 request thêm so với cách 1. Chấp nhận được vì payload nhỏ và xảy ra ít.

> **Bài học**: WS notify *khi* có thay đổi. REST quyết định *cái gì* đã đổi. Đừng để 2 source data lệch nhau.

### NotificationBell UI

```tsx
<button onClick={() => setOpen(v => !v)}>
    <Bell />
    {count > 0 && <span className="badge">{count > 99 ? "99+" : count}</span>}
</button>

{open && (
    <div className="dropdown">
        {list.map(n => (
            <Link to={linkFor(n)} onClick={() => markRead.mutate(n.id)}>
                {iconFor(n.type)}
                <p className={!n.isRead ? "font-semibold" : ""}>{n.title}</p>
                <p>{n.body}</p>
                <p>{timeAgo(n.createdAt)}</p>
            </Link>
        ))}
    </div>
)}
```

**Pattern:**
- Badge `99+` khi quá 99 — không bao giờ hiện `999+`.
- Click item → mark-read + navigate. Background thread, không block.
- Item chưa đọc có background nhạt + font đậm — visual cue quen thuộc.

---

## 14. Luồng end-to-end cho từng loại notification

### A. TRIP_MEMBER_ADDED — user-triggered

```
1. User A (owner) gọi POST /api/v1/trips/123/members { userId: 7 }
   ↓
2. TripMemberServiceImpl.addMember(ownerId, 123, request)
   - Validate ownership, không trùng member
   - tripMemberRepository.save(member)
   - eventPublisher.publishEvent(TripMemberAddedEvent(7, 123, "Đà Nẵng 3 ngày", "Alice"))
   - return TripMemberResponse
   ↓
3. Transaction commit (PHP của Spring đang xử lý request)
   ↓
4. @TransactionalEventListener AFTER_COMMIT fires
   NotificationEventListener.onTripMemberAdded(event):
     [1] notificationService.create(7, TRIP_MEMBER_ADDED, ...) → INSERT notifications row
     [2] messagingTemplate.convertAndSendToUser("7", "/queue/notifications", dto)
   ↓
5a. (Nếu user 7 đang online qua WS):
    Spring tìm session có Principal.getName()=="7" → push dto
    FE useNotificationSocket nhận → toast + invalidate list/count
    NotificationBell badge tăng từ N → N+1
5b. (Nếu user 7 offline):
    WS push thất bại / không có session → log warn
    Khi user 7 mở app sau: NotificationBell mount → useNotificationList fetch → thấy noti mới
```

### B. AI_CREDITS_LOW — user-triggered, conditional

```
1. User gọi POST /api/v1/trips/generate
   ↓
2. TripServiceImpl.generateTrip:
   - Gọi Gemini, sinh trip
   - user.aiCredits -= 1
   - userRepository.save(user)
   - if (remainingCredits <= 1): publishEvent(AiCreditsLowEvent(userId, 1))
   ↓
3. Transaction commit
   ↓
4. AFTER_COMMIT listener: tạo noti + push WS
```

### C. TRIP_REMINDER — scheduler-triggered

```
8:00:00 sáng Hà Nội mỗi ngày:
1. TripReminderScheduler.sendTripReminders()
   - tomorrow = today + 1
   - trips = tripRepository.findByDateStart(tomorrow)
   - for each: publishEvent(TripReminderEvent(...))
   ↓
2. Event đi qua publisher (không có transaction → dùng @EventListener thường)
3. Listener: save + push
```

### D. WEATHER_ALERT — scheduler + external API + dedup

```
7:00:00 sáng mỗi ngày:
1. WeatherAlertScheduler.scanForWeatherAlerts()
   - upcoming = trips dateStart trong 5 ngày tới
   - foreach trip:
       - weather = externalApiService.getWeather(destination, ...)
       - foreach forecast f:
           - skip nếu không phải BAD_CONDITION
           - skip nếu f.date ngoài range trip
           - skip nếu đã có noti tương ứng (dedup)
           - publishEvent(WeatherAlertEvent(...))
   ↓
2. Listener: save + push
```

---

## 15. Câu hỏi để học sâu hơn

Sau khi đọc xong, thử trả lời:

1. Tại sao không dùng `@EventListener(condition = ...)` thay vì `if` trong code?
2. Nếu user 7 đang mở 2 tab cùng lúc, mỗi tab có 1 WS connection. Cả 2 tab nhận noti hay chỉ 1?
3. Bảng `notifications` lớn dần theo thời gian. Làm sao xử lý? (Hint: TTL, archive)
4. Nếu `notificationService.create` throw exception trong listener, `messagingTemplate.convertAndSendToUser` còn được gọi không?
5. Tại sao không gửi cả `NotificationDto` qua WS rồi FE tự append vào list, để khỏi cần REST refetch?
6. Khi `aiCredits = 1` sau generate → user còn 0 sau generate nữa → có gửi noti `AI_CREDITS_LOW` thêm 1 lần không? Có spam không?
7. `@Scheduled` chạy trên mọi instance khi scale → ShedLock fix bằng cách nào (cao level)?
8. Tại sao `connectHeaders` đi qua được SockJS dù SockJS dùng HTTP polling fallback?

### Đáp án gợi ý

1. `condition` dùng SpEL, debug khó. `if` trong code rõ ràng hơn cho người đọc.
2. Cả 2. Mỗi WS session là user destination độc lập; `convertAndSendToUser` broadcast tới *mọi* session có cùng `Principal.getName()`.
3. Pruning job: xóa noti `isRead=true` cũ hơn 90 ngày. Hoặc partition table theo tháng.
4. Có, vì `push()` được gọi trên dòng tiếp theo trong listener. Tuy nhiên nếu `create()` throw, dòng `push()` không chạy. Để chắc chắn — cần thiết kế: save trong try-catch, push trong try-catch riêng. (Hiện tại nếu DB lỗi thì noti không tồn tại, không push là đúng).
5. Có thể làm. Nhưng pagination/sort/dedup phức tạp, đặc biệt khi reorder. Refetch đơn giản, đúng đắn, đủ nhanh.
6. Có. Mỗi generate khi `remaining ≤ 1` đều gửi. Để chống spam: thêm cooldown (vd "chỉ gửi mới khi noti cũ đã đọc / quá 24h").
7. Tất cả instance tranh nhau acquire 1 lock row trong DB. Chỉ instance nào lấy lock được mới chạy. Sau khi xong nhả lock.
8. SockJS thay protocol nhưng vẫn giả lập STOMP frames ở application layer; CONNECT frame chứa header `Authorization` không phụ thuộc transport.

---

## Tài liệu tham khảo bên ngoài

- [Spring WebSocket STOMP docs](https://docs.spring.io/spring-framework/docs/current/reference/html/web.html#websocket-stomp)
- [Spring `@TransactionalEventListener`](https://docs.spring.io/spring-framework/docs/current/reference/html/core.html#context-functionality-events-annotation)
- [STOMP protocol spec](https://stomp.github.io/)
- [SockJS protocol](https://github.com/sockjs/sockjs-protocol)
- [JPA `@Modifying` queries](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/#jpa.modifying-queries)

---

*File này phản ánh design tại thời điểm xây notification module. Khi mở rộng (thêm loại noti / scale / hệ thống event bus thật) — cần đánh giá lại.*
