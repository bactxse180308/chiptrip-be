# Năm bản sửa P0: triển khai và kiểm thử

Ngày: 20/09/2026. Phạm vi: backend và phần frontend cần đồng bộ API.
Các thay đổi nằm trong working tree, chưa commit/deploy. Không chạy với database ứng dụng thật hoặc gọi dịch vụ trả phí.

## 1. OTP sai phải được lưu

- `AuthServiceImpl.verifyOtp` và `resetPasswordWithOtp` tạm ngưng transaction bên ngoài bằng `NOT_SUPPORTED`, sau đó chạy transaction ghi riêng qua `TransactionTemplate`.
- Query lấy OTP sử dụng `PESSIMISTIC_WRITE`: các request cùng mã phải lần lượt kiểm tra, tránh mất counter hoặc dùng thành công hai lần.
- Sai mã: tăng attempts, trả kết quả từ transaction, commit, rồi mới throw lỗi HTTP. Không dùng `noRollbackFor` rộng cho mọi lỗi nghiệp vụ.
- Đúng mã: consume OTP, thay đổi user và vô hiệu refresh token trong cùng transaction. Lỗi hệ thống vẫn rollback toàn bộ.

```text
Request -> Spring proxy -> write transaction -> lock OTP -> validate
  sai: attempts + 1 -> commit -> HTTP error
  đúng: consume + account changes -> commit -> success
  lỗi hệ thống: rollback -> error
```

## 2. Account bị khóa

- REST: chỉ tạo authentication khi user hiện tại enabled, không bị khóa/hết hạn. User đã xóa trở thành anonymous, không làm lỗi 500.
- JWT cũ không qua được protected endpoint sau khi khóa; endpoint public vẫn truy cập được như khách.
- STOMP CONNECT từ chối user disabled. SUBSCRIBE kiểm tra lại trạng thái hiện tại.
- `WebSocketAccountGuard` kiểm tra DB trước mỗi outbound MESSAGE, nên subscription đã mở cũng không nhận nội dung khi user bị khóa. Xóa registry khi disconnect.
- Không chủ động đóng socket; heartbeat/disconnect vẫn hoạt động. Khi admin mở khóa, token chưa hết hạn và session còn sống có thể dùng lại. Đây không phải cơ chế revoke token vĩnh viễn.
- Tradeoff: thêm query trạng thái trên mỗi message/session đích; cần đo tải trước khi scale. Không cache trạng thái tùy tiện vì sẽ tạo khoảng trễ khóa tài khoản.

## 3. STOMP deny-by-default

- Chặn mọi client SEND vì hiện dự án dùng REST để gửi chat, STOMP chỉ nhận push; chưa có `@MessageMapping` hợp lệ cho client publish.
- SUBSCRIBE chỉ cho phép đúng bốn queue `/user/queue/messages`, `/user/queue/notifications`, `/user/queue/trip-generation`, `/user/queue/trip-enrichment`.
- `/topic/support` chỉ dành cho ADMIN; không dùng prefix match. Raw queue, wildcard và topic ngoài danh sách bị chặn.
- DISCONNECT, UNSUBSCRIBE và heartbeat được giữ; command khác bị từ chối. Sau này thêm destination phải bổ sung policy và regression test.

## 4. Public/shared trip

- `TripReadOnlyResponse` là DTO allowlist độc lập, không kế thừa owner DTO. Public/shared không trả email, shareToken, members, checklist, budget hoặc premium snapshot.
- Public còn bỏ ngày đi/ngày về/ngày của itinerary, lifecycle status và booking URL. Shared vẫn có ngày cụ thể và booking URL vì người đọc được cấp link riêng.
- Public feed trả `durationDays` thay `dateStart/dateEnd`; frontend đã đổi type và mapper theo contract này.
- Chủ trip vẫn có owner-detail; quyền xem/sửa owner không nới lỏng.
- Clone trip của người khác không sao chép checklist/ngân sách/booking URL/ngày riêng tư. Bản sao bắt đầu từ hôm nay theo giờ Việt Nam, giữ số ngày, ngân sách khởi tạo 0. Chủ trip clone chính mình vẫn giữ dữ liệu.
- Token mới dạng `v2_` + 32 ký tự hex. Tất cả token định dạng cũ bị từ chối, kể cả đã lưu trong DB, để vô hiệu các link có thể từng lộ qua DTO/log. Owner-detail không trả lại token cũ; chủ trip cần tạo link mới.
- Bỏ log token khi enableShare; request logging/JWT/exception/401 che đoạn capability ở URL shared/invite. Cần kiểm tra riêng reverse proxy/APM/access log trên môi trường deploy; code không xóa log cũ.
- Đây là bảo vệ theo field, không tự phát hiện thông tin cá nhân người dùng nhập trong title/description hoặc ảnh/URL. Invite-token policy không thay đổi trong đợt này.

## 5. Grant credit không mất phần lẻ

- Admin dùng một câu UPDATE cộng `amount * 100` vào canonical `aiCreditUnits`; chỉ fallback `aiCredits * 100` cho bản ghi legacy có units NULL.
- Cập nhật whole-credit từ số units mới bằng chia nguyên, không lấy số whole cũ làm nguồn chuẩn. Ví dụ 75 units + 1 credit = 175 units = 1,75 credit.
- Validate amount dương, giới hạn phép nhân và kiểm tra overflow tổng trong query; affected rows phải bằng 1. Sau bulk update đọc lại để trả số dư mới, không save entity cũ.
- `User.@DynamicUpdate` tránh profile/login chỉ sửa field khác nhưng ghi đè credit đang được cộng đồng thời. Không xem đây là bảo đảm cho mọi detached merge hoặc admin đặt lại số dư tuyệt đối.
- Payment cũng dùng repository này nên phải kiểm tra affected rows; không được đánh dấu cộng tiền thành công nếu grant bị từ chối. Exception thoát khỏi transaction settlement để rollback.

## Kiểm thử

Các bộ regression chính:

| Nhóm | Phạm vi kiểm tra |
|---|---|
| `AuthOtpTransactionTest` | Commit counter khi trả lỗi, giới hạn 5 lần với request đồng thời, mã hết hạn, consume đúng một lần, reset/revoke và rollback |
| `CreditPersistenceTest` | Phần lẻ, whole bị lệch, legacy NULL, overflow, rollback, grant/grant, grant/spend, profile/grant đồng thời |
| `JwtAuthFilterTest`, `JwtSecurityChainTest` | JWT ký thật qua production SecurityFilterChain/MockMvc, account enabled/disabled/deleted, 401/403 và public endpoint |
| `JwtChannelInterceptorTest`, `WebSocketAccountGuardTest`, `WebSocketChannelIntegrationTest` | CONNECT/SUBSCRIBE/SEND policy, role, session cleanup, production inbound/outbound channel configuration |
| `TripServiceImplTest`, `RequestLogPathTest` | JSON public/shared với Jackson 3, nested dates/URL, owner fields, clone, token rotation/revocation và URL redaction |
| `UserTest`, `UserServiceImplTest`, `PaymentServiceImplTest` | Balance helper, admin orchestration/validation, webhook từ chối khi không grant được |
| Frontend `trip-mapper.test.ts` | Public itinerary không ngày riêng tư; shared vẫn map được ngày đã cho phép |

Test DB dùng Spring/JPA thật, không mock commit/rollback; SQL Server được tạo trong container riêng, không dùng URL/credential ứng dụng. Chọn `sqlserver` sẽ fail nếu Docker không hoạt động, không tự chuyển sang H2. Test schema mới không chứng minh migration trên database production cũ.

Lệnh backend (PowerShell, tại thư mục `Chiptrip-be`):

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\mvnw.cmd -B -o verify
.\mvnw.cmd -B -o '-Dchiptrip.test.database=sqlserver' verify
```

`-o` chỉ dùng khi dependency đã cache. Máy này cần Windows certificate store khi tải Maven dependency qua mạng: `$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'`; không tắt kiểm tra TLS.

Frontend (tại `chip-trip-ai-planner`): `npm test`, `npm run typecheck`, `npm run lint`, `npm run build`.

### Kết quả cuối

| Lệnh | Kết quả ngày 20/09/2026 |
|---|---|
| Backend `-Dchiptrip.test.database=sqlserver verify` | BUILD SUCCESS; 149 test, 0 failures/errors/skipped, 46 giây. Trong đó 7 test OTP và 6 test credit chạy DB SQL Server 2022 thật (13 test DB) |
| Backend `verify` mặc định H2 | BUILD SUCCESS; 149 test, 0 failures/errors/skipped, 18 giây |
| Frontend `npm test` | 29/29 test pass trong 8 file |
| Frontend `npm run typecheck` | Pass |
| Frontend `npm run lint` | 0 error; 11 warning tại các file không sửa trong đợt này |
| Frontend `npm run build` | Pass; còn cảnh báo bundle lớn và Browserslist cũ |
| `git diff --check` ở hai repository | Pass |

Các warning khi SQL Server container vừa khởi động (prelogin connection retry) hết khi database sẵn sàng. Schema test dùng create-only với halt-on-error; không bỏ qua lỗi tạo schema. Testcontainers dọn container khi kết thúc tiến trình.

JWT/REST dùng production filter chain nhưng endpoint fixture; STOMP dùng production channel config nhưng chưa có browser/socket thật. Webhook failure là unit test orchestration, không phải integration toàn bộ payment settlement. Các kết quả này không thay thế kiểm thử staging/migration/load test.

## Triển khai

1. Deploy backend và frontend tương thích cùng đợt; public feed đổi contract ngày thành duration.
2. Thông báo link chia sẻ cũ hết hiệu lực; chủ trip tạo link mới. Cột share_token hiện dài 64 đủ chứa token mới, không cần migration schema cho bản sửa này.
3. Không rollback đơn thuần về binary cũ: sẽ mở lại các lỗ hổng và có thể cho phép token legacy. Nếu cần rollback vận hành, phải giữ bản vá bảo mật hoặc thu hồi token trong DB theo quy trình riêng.
4. Rà soát cache/CDN và log cũ có response/token nhạy cảm; việc bỏ field không thu hồi được dữ liệu đã bị tải về. Không khẳng định từng có sự cố khai thác production.
5. Chưa kiểm thử qua browser thật/SockJS network hay reverse proxy triển khai, chưa load test; test kênh STOMP là integration trong Spring, không phải end-to-end qua socket.
