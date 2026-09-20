# Năm vấn đề P0: nguyên nhân và hướng xử lý

Ngày đối chiếu code: 19/09/2026. Phạm vi: ChipTrip backend.

**Đây là bản phân tích gốc ngày 19/09/2026. Năm vấn đề đã được triển khai sửa trong working tree ngày 20/09/2026; chưa deploy. Kết quả và thay đổi tương thích được ghi tại [Báo cáo triển khai và kiểm thử](p0-implementation-verification.md).**

Tài liệu tập trung đúng 5 vấn đề được yêu cầu, giữ mã P0-01 đến P0-05 của báo cáo audit. Các đường dẫn/dòng tham chiếu là snapshot tại thời điểm đọc; khi triển khai phải kiểm tra diff mới nhất.

## Tóm tắt

| Mã | Vấn đề | Nguyên nhân gốc | Hướng xử lý chính |
|---|---|---|---|
| P0-01 | OTP sai không tích lũy attempts | Ghi counter và throw RuntimeException trong cùng transaction | Commit kết quả xác minh thất bại trước khi chuyển thành HTTP error; serialize truy cập OTP |
| P0-02 | Account bị khóa vẫn dùng JWT | Tự tạo authenticated principal, bỏ qua enabled status | Kiểm tra trạng thái hiện tại ở cả REST và STOMP; xử lý session WS đã mở |
| P0-03 | Client gửi trực tiếp vào broker | Chỉ kiểm tra CONNECT/SUBSCRIBE, bỏ qua SEND | Chính sách inbound deny-by-default theo command/destination |
| P0-04 | Public trip lộ dữ liệu riêng | Dùng chung owner-detail DTO và mapper | DTO/projection public và shared có allowlist riêng |
| P0-05 | Admin grant mất credit lẻ | Tính từ whole-credit rồi ghi đè paid units | Atomic increment trên units; không save entity cũ sau bulk update |

**Độ chắc chắn:** các nhánh code dưới đây đã được đọc lại. Chưa chạy exploit, STOMP client giả lập, database integration test hay thử concurrent requests trong lần làm tài liệu này. Phân tích transaction dựa trên annotation và quy tắc Spring; ảnh hưởng concurrency cần xác nhận trên SQL Server cô lập. Không khẳng định đã có người khai thác hay đã mất tiền trong production.

## 1. P0-01: OTP sai tăng attempts rồi bị rollback

### 1.1. Hiểu đơn giản

Transaction giống một nhóm thay đổi được chốt cùng nhau: hoặc commit để lưu, hoặc rollback để hủy. Gọi repository.save() không đồng nghĩa transaction đã commit.

Code cần lưu việc người dùng nhập sai, nhưng lại xử lý nó như lỗi phải hủy toàn bộ transaction. Vì vậy, lần sau database vẫn có số attempts cũ.

### 1.2. Bằng chứng

- [AuthServiceImpl.java:191](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/auth/service/impl/AuthServiceImpl.java:191): verifyOtp có @Transactional.
- [AuthServiceImpl.java:209](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/auth/service/impl/AuthServiceImpl.java:209): chặn nếu attempts >= 5; nhánh sai ở dòng 213 tăng attempts, save, rồi throw AppException.badRequest.
- [AuthServiceImpl.java:236](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/auth/service/impl/AuthServiceImpl.java:236): resetPasswordWithOtp cũng @Transactional; lặp lại nhánh này tại dòng 252.
- [AppException.java:9](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/common/exception/AppException.java:9): AppException kế thừa RuntimeException.

Mặc định Spring rollback khi RuntimeException thoát qua transaction boundary. Đây là quy tắc cần đối chiếu cùng annotation thực tế, không phải do repository.save bị bỏ qua. [Spring: transaction rollback](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)

### 1.3. Luồng gây lỗi

```text
Client gửi OTP sai
  -> Controller -> Spring transaction proxy -> AuthService
  -> Đọc attempts = 0
  -> Tăng attempts = 1, gọi save
  -> Throw AppException
  -> Spring rollback transaction
  -> Client nhận lỗi; database vẫn attempts = 0
  -> Request sau lại đọc attempts = 0
```

Counter không tiến đến ngưỡng 5 qua các lần sai tuần tự. Thông báo “còn 4 lần” có thể lặp lại.

Ảnh hưởng: lớp chống thử nhiều mã bị mất hiệu lực, tăng rủi ro brute-force trong thời gian OTP còn hạn. Điều này không có nghĩa OTP tồn tại vô hạn hay mọi rate-limit khác đều bị vô hiệu.

### 1.4. Hướng xử lý đề xuất

**Ưu tiên: transaction trả kết quả nghiệp vụ, lớp ngoài mới tạo HTTP error.**

1. Facade không bao một outer transaction quanh toàn bộ lời gọi.
2. Gọi một transactional bean riêng, hoặc TransactionTemplate có boundary rõ.
3. Trong transaction: lấy OTP theo email/purpose, khóa bản ghi hoặc dùng cơ chế cập nhật có điều kiện phù hợp.
4. Kiểm tra used, expiresAt và attempts sau khi đã đồng bộ truy cập.
5. OTP sai: tăng attempts rồi trả kết quả INVALID/TOO_MANY_ATTEMPTS, không throw lỗi nghiệp vụ trong nhánh này.
6. Transaction commit xong; facade chuyển kết quả thất bại thành AppException/HTTP response.
7. OTP đúng: mark used và hành động được bảo vệ cùng transaction. Với reset password, gồm đổi password và revoke refresh tokens; với email verification, gồm cập nhật verified.
8. Nếu lỗi kỹ thuật xảy ra trong nhánh thành công, rollback toàn bộ thay đổi của nhánh đó.

```text
OTP sai:
Facade -> Transactional verifier -> tăng attempts -> trả INVALID -> COMMIT
       <- nhận INVALID -> chuyển thành HTTP error

OTP đúng:
Facade -> Transactional use case
       -> validate + consume OTP + đổi password/revoke tokens -> COMMIT
```

Khóa hoặc conditional update còn giải quyết hai request cùng đọc attempts=4 rồi ghi lại 5. Bản sửa không chỉ cần “counter được lưu”, mà phải bảo vệ budget và one-time use khi có concurrency.

**Không nên làm:**
- Đổi save thành saveAndFlush rồi giữ nguyên throw: flush không phải commit.
- Thêm noRollbackFor=AppException cho toàn AuthService: có thể commit các thay đổi dở dang khác.
- Thêm REQUIRES_NEW vào private method hoặc gọi qua this rồi cho rằng proxy sẽ áp dụng.
- Cho outer transaction giữ khóa OTP rồi gọi inner REQUIRES_NEW để cập nhật cùng dòng: có nguy cơ chờ khóa.
- Bỏ @Transactional hoàn toàn và để consume OTP, đổi password, revoke refresh commit rời rạc.
- Consume OTP thành công ở transaction riêng trước khi password update commit.

### 1.5. Phạm vi và kiểm chứng

Ảnh hưởng: AuthServiceImpl, OTP repository, có thể thêm transactional verifier/use-case bean, auth tests. Không bắt buộc đổi request/response schema hoặc database schema. Error code cần nhất quán với frontend.

Checklist trước khi đóng issue:
- [ ] Một lần sai: đọc database từ transaction khác thấy attempts tăng đúng 1.
- [ ] Sai đủ 5 lần: lần sau bị chặn, kể cả gửi đúng mã của OTP đã hết budget.
- [ ] OTP expired/used không đổi password hoặc email verification.
- [ ] Hai request dùng cùng OTP đúng: chỉ một lần consume thành công.
- [ ] Concurrent sai/đúng gần ngưỡng: kết quả theo thứ tự được serialize, không vượt budget.
- [ ] DB failure trong nhánh reset: password, used và refresh revocation cùng rollback.
- [ ] Test đi qua Spring proxy và SQL Server, không chỉ mock repository hay gọi new AuthServiceImpl.

## 2. P0-02: JWT REST và STOMP chưa chặn tài khoản bị khóa

### 2.1. Hiểu đơn giản

JWT hợp lệ chỉ nói rằng token được phát hành hợp lệ và chưa hết hạn. Nó không tự biết admin vừa khóa tài khoản trong database.

Authentication là xác định danh tính. Account status là điều kiện cho phép danh tính đó tiếp tục truy cập. Hai kiểm tra cần cùng tồn tại.

### 2.2. Bằng chứng

- [JwtAuthFilter.java:43](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/common/security/JwtAuthFilter.java:43): load UserDetails rồi tạo UsernamePasswordAuthenticationToken bằng constructor có authorities; không kiểm tra isEnabled.
- [UserDetailsServiceImpl.java:18](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/common/security/UserDetailsServiceImpl.java:18): load theo email và truyền isActive vào UserPrincipal, không từ chối disabled user.
- [UserPrincipal.java:31](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/common/security/UserPrincipal.java:31): isEnabled trả active nhưng không được hai đường JWT sử dụng.
- [JwtChannelInterceptor.java:75](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/notification/config/JwtChannelInterceptor.java:75): CONNECT kiểm tra userId khớp token, sau đó tạo authenticated principal, thiếu enabled check.
- [UserServiceImpl.java:290](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/user/service/impl/UserServiceImpl.java:290): adminToggleStatus lưu isActive và revoke refresh khi disable.

Đây là đường xác thực JWT tùy biến, không đi qua đầy đủ kiểm tra account của luồng username/password chỉ vì đã có UserDetails.isEnabled.

### 2.3. Luồng gây lỗi

```text
User login -> nhận access token và refresh token
Admin khóa account -> is_active=false, revoke refresh
User gửi access token cũ
  -> JWT signature/expiry hợp lệ
  -> DB trả UserDetails(enabled=false)
  -> Filter vẫn tạo authenticated principal
  -> Protected endpoint coi user đã xác thực
```

WS CONNECT mới với token còn hạn đi qua cùng kiểu thiếu kiểm tra. Với kết nối đã mở từ trước, chỉ sửa CONNECT chưa đủ: session có principal riêng và không tự nhận thay đổi trạng thái database.

### 2.4. Hướng xử lý đề xuất

1. Sau khi xác thực JWT và lấy user hiện tại, kiểm tra account enabled trước khi đặt Authentication vào SecurityContext hoặc STOMP accessor.
2. Dùng chung account-status policy ở REST và WS để tránh mỗi bên định nghĩa “được hoạt động” khác nhau.
3. Không chỉ kiểm tra claim active được nhúng lúc phát token: claim đó cũng cũ sau khi khóa account.
4. Chọn error contract rõ ràng. Đề xuất protected REST trả 401 với code ổn định như ACCOUNT_DISABLED; nếu chọn 403 phải thống nhất frontend. Đây là đề xuất, chưa phải code hiện tại.
5. Filter error cần đi qua entry point/response handling phù hợp. Không mặc định @RestControllerAdvice sẽ bắt exception xảy ra trước controller.
6. Không gọi protected controller sau khi xác thực thất bại. Giữ chính sách public endpoints riêng và nhất quán với cách xử lý token invalid hiện có.
7. Chốt policy WS session đang mở: yêu cầu disconnect khi admin disable, hoặc cơ chế revalidation có độ trễ giới hạn.
8. Chỉ kiểm tra các inbound frame tiếp theo chưa ngăn được session đã subscribe tiếp tục nhận server push. Muốn revoke ngay phải đóng session hoặc có outbound delivery guard phù hợp.
9. Sự kiện disconnect nên phát sau khi transaction disable commit. Với nhiều replica, cần truyền revocation tới node đang giữ session; không hứa xử lý liên node bằng map local.

Vì REST đã truy vấn user mỗi request, kiểm tra enabled không bắt buộc thêm một DB query mới. Không cần lập tức xây JWT blacklist cho bản sửa này.

### 2.5. Phạm vi và kiểm chứng

Ảnh hưởng: JwtAuthFilter, account policy/UserDetailsService nếu cần, JwtChannelInterceptor, admin disable flow và WS lifecycle. Không cần đổi premium logic, JWT claims hay schema cho bản sửa cơ bản. Session revocation có thể là PR tiếp theo nhưng phải công bố giới hạn trong thời gian chuyển tiếp.

- [ ] Login rồi disable: token đã phát bị chặn tại protected REST.
- [ ] Disabled user không CONNECT thành công vào STOMP.
- [ ] Session WS đã mở không tiếp tục nhận dữ liệu sau thời hạn revocation đã cam kết.
- [ ] Active user vẫn truy cập bình thường; ROLE_ADMIN vẫn được kiểm tra độc lập.
- [ ] Token expired/malformed và user không còn tồn tại không gây 500 ngoài dự kiến.
- [ ] Refresh token đã revoke không phát access token mới.
- [ ] Public endpoints hoạt động đúng policy, không vô tình bắt tất cả anonymous phải login.

Cần quyết định thêm: khi enable lại account, token cũ chưa hết hạn có được dùng lại không? Enabled-check đơn thuần cho phép điều đó; nếu cần bắt login lại phải bổ sung token/session version hoặc quy tắc revocation khác, không ngầm coi đã được giải quyết.

## 3. P0-03: STOMP chưa chặn client SEND vào broker

### 3.1. Hiểu đơn giản

CONNECT là vào kết nối, SUBSCRIBE là xin nghe một kênh, SEND là gửi nội dung vào một destination. Cấm nghe kênh admin không đồng nghĩa cấm gửi tin vào kênh admin.

Broker là bộ phận phân phối message. Các kênh server dùng để phát sự kiện không nên mặc định nhận dữ liệu tùy ý từ client.

### 3.2. Bằng chứng

- [JwtChannelInterceptor.java:50](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/notification/config/JwtChannelInterceptor.java:50): switch chỉ xử lý CONNECT và SUBSCRIBE; các frame khác được cho qua.
- [JwtChannelInterceptor.java:87](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/notification/config/JwtChannelInterceptor.java:87): SUBSCRIBE chỉ bảo vệ prefix /topic/support.
- [WebSocketConfig.java:38](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/notification/config/WebSocketConfig.java:38): enableSimpleBroker cho /topic và /queue; application prefix /app; user prefix /user.
- [ChatServiceImpl.java:52](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/chat/service/impl/ChatServiceImpl.java:52): server dùng /topic/support cho admin pool.
- Khi tìm trong source hiện tại, không thấy @MessageMapping hay một authorization interceptor khác để bù phần SEND thiếu. Cần tìm lại khi triển khai nếu code thay đổi.

Tài liệu Spring cũng phân biệt quyền gửi MESSAGE và quyền SUBSCRIBE, đặc biệt với broker destinations. [Spring Security: WebSocket authorization](https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html)

### 3.3. Luồng rủi ro

```text
Client USER CONNECT bằng JWT hợp lệ
  -> Gửi STOMP SEND destination=/topic/support
  -> Interceptor không kiểm tra SEND
  -> Simple broker nhận message
  -> Admin đang subscribe có thể nhận payload do client giả tạo
```

Tác động trực tiếp cần kiểm chứng: giả sự kiện/tin nhắn realtime, gây nhầm lẫn UI hoặc spam. Không kết luận đường này tự ghi được tin nhắn vào database hay thực thi admin action; đó là những hành vi khác chưa được chứng minh.

### 3.4. Hướng xử lý đề xuất

**Ưu tiên policy nhỏ, rõ trên clientInboundChannel, không thay transport toàn hệ thống.**

Theo source đang đọc, frontend dùng WS để nhận push; chưa thấy handler business cần client SEND. Bản sửa ban đầu có thể từ chối mọi client SEND sau khi xác nhận toàn bộ client đang dùng. Nếu sau này có /app handler, chỉ mở các route được định nghĩa và có authorization.

| Frame/destination | Chính sách đề xuất |
|---|---|
| CONNECT | JWT hợp lệ, user enabled, bind principal do server xác định |
| SUBSCRIBE /topic/support | ADMIN đang hợp lệ; match destination được cho phép |
| SUBSCRIBE /user/queue/messages | User authenticated, queue cá nhân được Spring resolve |
| SUBSCRIBE /user/queue/notifications | User authenticated, queue cá nhân |
| SUBSCRIBE /user/queue/trip-generation | User authenticated, queue cá nhân |
| SUBSCRIBE /user/queue/trip-enrichment | User authenticated, queue cá nhân |
| SUBSCRIBE trực tiếp /queue/** hoặc /user/{otherId}/** | Từ chối |
| SEND /topic/**, /queue/**, /user/** | Từ chối, kể cả role ADMIN |
| SEND /app/** | Hiện mặc định từ chối; chỉ mở exact application handlers khi thật sự có nhu cầu |
| Destination không biết | Từ chối |
| Heartbeat, DISCONNECT, UNSUBSCRIBE và control frames | Xử lý theo protocol/session đã xác thực; không chặn mù mọi frame |

Các queue trên đã đối chiếu với frontend integrations/ws hiện tại. Không dùng wildcard /user/** quá rộng và cho rằng mọi destination bên dưới đều là “của tôi”.

Client-supplied userId/from/role trong payload không được dùng làm danh tính. Quyền server publish khác quyền admin client: ADMIN không tự động được giả message của backend.

Có thể tiếp tục dùng interceptor hiện có cho hotfix hoặc chuyển sang Spring Messaging AuthorizationManager khi có test đầy đủ. Không cần cả hai policy chồng chéo thiếu thứ tự; authentication phải chạy trước authorization. Nếu đưa thêm WebSocket Security configuration, kiểm tra tác động CSRF/CONNECT và thứ tự interceptor, không bật/tắt hàng loạt để né test fail.

### 3.5. Phạm vi và kiểm chứng

Ảnh hưởng: JwtChannelInterceptor, WebSocketConfig và WS tests; kiểm tra frontend destinations nhưng không mặc định phải sửa frontend. Không schema change.

- [ ] USER SEND vào /topic/support bị từ chối; ADMIN subscriber không nhận payload đó.
- [ ] ADMIN SEND vào server-only destination cũng bị từ chối.
- [ ] USER không subscribe /topic/support, raw /queue hoặc destination user khác.
- [ ] Backend vẫn push thành công vào notification/chat/generation/enrichment queues.
- [ ] Hai user riêng biệt không nhận message của nhau.
- [ ] Heartbeat, unsubscribe và disconnect không bị policy mới làm hỏng.
- [ ] CONNECT không hợp lệ/disabled không tạo session được phép subscribe.
- [ ] Test với client/broker thật trong môi trường cô lập, không chỉ gọi preSend bằng mock.

## 4. P0-04: Public trip dùng DTO chứa thông tin riêng

### 4.1. Hiểu đơn giản

DTO quyết định server trả những trường nào cho client. DTO phù hợp với chủ trip không tự động phù hợp với người xem công khai.

“Frontend không render email/token” không phải biện pháp bảo mật: dữ liệu vẫn có trong HTTP response và xem được bằng Network tab hoặc API client.

### 4.2. Bằng chứng

- [SecurityConfig.java:73](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/common/config/SecurityConfig.java:73): GET /api/v1/trips/*/public được permitAll.
- [TripSocialController.java:50](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/trip/controller/TripSocialController.java:50): endpoint trả TripDetailResponse.
- [TripServiceImpl.java:835](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/trip/service/impl/TripServiceImpl.java:835): getPublicTrip kiểm tra isPublic, rồi dùng buildTripDetailResponse.
- [TripServiceImpl.java:922](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/trip/service/impl/TripServiceImpl.java:922): mapper đưa shareToken vào response; dòng 930-938 đưa user email, members và checklist.
- [TripServiceImpl.java:827](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/trip/service/impl/TripServiceImpl.java:827): shared endpoint tìm theo token và cũng dùng owner-detail mapper.
- [TripServiceImpl.java:812](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/trip/service/impl/TripServiceImpl.java:812): enableShare còn ghi token trực tiếp vào log.

Các trường ngày đi, budget, checklist, danh sách thành viên cần privacy policy rõ. Email và capability token không nên bị công khai mặc định chỉ vì isPublic=true.

### 4.3. Vì sao lộ share token nghiêm trọng hơn lộ metadata?

Share token là capability: ai sở hữu nó có thể dùng quyền xem mà endpoint shared cho phép, tương tự chìa khóa của link chia sẻ.

```text
Owner đã bật share -> trip có shareToken T
Owner publish trip
  -> Anonymous gọi public API -> response lộ T
Owner unpublish nhưng không disable share
  -> Public endpoint không còn trả trip
  -> Người đã giữ T vẫn gọi shared endpoint được
```

Shared link còn hiệu lực sau unpublish có thể là hành vi sản phẩm chủ đích. Lỗi ở đây là public API phát tán chìa khóa cho những người không được gửi link riêng. Nếu trip chưa từng bật share thì token có thể null; không phải mọi public trip đều có token dùng được.

### 4.4. Hướng xử lý đề xuất

1. Tạo PublicTripResponse theo allowlist: chỉ các trường đã xác nhận được công khai.
2. Tách public mapper/projection khỏi owner-detail mapper. Không dựng owner DTO rồi set vài field thành null vì dễ quên field mới.
3. SharedTripResponse có policy riêng, không mặc định ngang owner hoặc public. Chỉ “có link” không tự cấp quyền quản lý.
4. Không trả shareToken trong public DTO. Owner lấy token qua API quản lý share có ownership check; shared DTO cũng không cần lặp token trong body.
5. Chốt chính sách ngày chính xác, itinerary notes, members, checklist, budget trước khi whitelist. Kiểm tra nested DayDetail/ActivityDetail/UserInfo, không chỉ top-level.
6. Dùng public profile fields được phép; không đưa owner.email theo DTO đầy đủ.
7. Kiểm kê token có thể đã lộ và lập kế hoạch revoke/rotate có thông báo. Ẩn token từ hôm nay không thu hồi token người khác đã lưu.
8. Loại token khỏi logs, kể cả raw URI của /shared/{token}; rà soát nơi log error/request. Không đọc hoặc xuất log nhạy cảm để “chứng minh” nếu không có quyền/phạm vi cần thiết.
9. Kiểm tra frontend API types/mappers và caches. Cache public response cũ có thể vẫn chứa dữ liệu sau khi backend đổi.

### 4.5. Ma trận quyền đề xuất cần chốt

| Dữ liệu | Anonymous public | Người có shared link | Owner |
|---|---|---|---|
| Title, destination, itinerary công khai | Cho phép theo field allowlist | Theo shared policy | Có |
| Public display name/avatar | Nếu được phép công khai | Nếu được phép | Có |
| Owner email | Không | Mặc định không | Theo owner API |
| Share management token | Không | Không trả lại trong body | Qua API quản lý share |
| Members/checklist/private notes | Mặc định không | Phải quyết định riêng | Có |
| Ngày đi/budget chính xác | Chưa chốt, không tự suy luận từ UI | Chưa chốt | Có |
| Edit/delete/invite management | Không | Không chỉ vì có link | Theo authorization hiện hành |

### 4.6. Phạm vi và kiểm chứng

Ảnh hưởng: DTO/controller/service contract public và shared, mappers, frontend types, share token logging. Đây là thay đổi response có thể breaking với client đang dựa field cũ; không nhất thiết đổi database schema.

- [ ] Anonymous GET public: assert danh sách key được phép, không chỉ kiểm tra email=null.
- [ ] Không có shareToken hoặc email trong nested JSON.
- [ ] Owner endpoints vẫn trả đúng dữ liệu owner cần.
- [ ] Public trip chưa share và đã share đều không lộ token.
- [ ] Unpublish chặn public read; shared read tuân thủ policy đã chốt.
- [ ] Disable/rotate share làm token cũ không còn truy cập được.
- [ ] Không log token khi enableShare hoặc gọi shared endpoint.
- [ ] FE build/typecheck và contract snapshots pass; không giữ cache response cũ ngoài dự kiến.

Không “rollback” privacy fix bằng cách phục hồi trường bí mật. Khi gặp lỗi client nghiêm trọng, cân nhắc tạm tắt public/shared surface thay vì mở lại rò rỉ.

## 5. P0-05: Admin cộng credit làm mất phần lẻ

### 5.1. Hiểu đơn giản

Backend dùng units để lưu chính xác: 100 units = 1 credit; đổi activity có thể trừ 25 units. Vì vậy 0.75 credit là số dư hợp lệ.

aiCredits là số credit nguyên phục vụ tương thích cũ. Nó không giữ được 0.25/0.50/0.75. Cộng tiền dựa vào cột số nguyên rồi ghi đè cột chính xác sẽ đánh mất phần lẻ.

### 5.2. Bằng chứng

- [UserServiceImpl.java:305](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/user/service/impl/UserServiceImpl.java:305): adminGrantCredits load user, gọi addWholeAiCredits, save entity.
- [User.java:125](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/user/entity/User.java:125): addWholeAiCredits dùng safeCredits()+amount rồi gọi setWholeAiCredits.
- [User.java:119](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/user/entity/User.java:119): setWholeAiCredits ghi aiCredits=safe và aiCreditUnits=safe*100.
- [User.java:109](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/user/entity/User.java:109): effectiveAiCreditUnits ưu tiên units, chỉ fallback legacy khi null.
- [UserRepository.java:75](D:/Chiptrip_Project_Full_stack/Chiptrip-be/src/main/java/com/tranbac/chiptripbe/module/user/repository/UserRepository.java:75): đã có addCredits bằng bulk arithmetic UPDATE, hiện được dùng cho luồng payment.

### 5.3. Ví dụ cụ thể

Giả sử aiCredits đã được đồng bộ xuống phần nguyên của units:

| Ban đầu | Admin cộng | Mong đợi | Luồng hiện tại có thể ghi |
|---|---|---|---|
| 75 units, aiCredits=0 | 1 credit | 175 units = 1.75 | 100 units = 1.00 |
| 125 units, aiCredits=1 | 2 credits | 325 units = 3.25 | 300 units = 3.00 |
| 250 units, aiCredits=2 | 1 credit | 350 units = 3.50 | 300 units = 3.00 |
| 200 units, aiCredits=2 | 1 credit | 300 units = 3.00 | 300 units = 3.00 |

Nếu aiCredits đã stale, sai lệch còn có thể theo hướng khác; lỗi gốc không chỉ là làm tròn xuống mà là dùng sai nguồn dữ liệu để tính số dư.

```text
Admin request amount=1
  -> Load User(units=75, aiCredits=0)
  -> safeCredits()+1 = 1
  -> setWholeAiCredits(1)
  -> units = 1*100 = 100
  -> save -> mất 75 units so với kết quả đúng
```

Đây là lỗi số nguyên/tương thích legacy, không phải sai số floating-point; đổi sang double không giải quyết.

### 5.4. Hướng xử lý đề xuất

**Bản sửa nhỏ trước: grant là phép cộng atomically trên balance hiện tại trong DB.**

1. Giữ validation amount là số credit nguyên dương, có giới hạn để tránh overflow; không âm thầm clamp dữ liệu sai thành 0.
2. Bảo đảm caller có quyền admin như hiện tại.
3. Dùng credit service/repository mutation chung; ưu tiên đánh giá và tái sử dụng addCredits hiện có, không copy thêm query gần giống.
4. Công thức nghiệp vụ:

```text
effectiveOldUnits = aiCreditUnits nếu khác null
                    ngược lại = aiCredits * 100
newUnits = effectiveOldUnits + amount * 100
legacyWholeCredits = phần nguyên của newUnits / 100
```

5. Cập nhật trên database bằng một statement atomic hoặc transaction/lock bảo đảm tương đương. Với query hiện có, kiểm tra đồng bộ legacy field trong trường hợp nó đã stale; không chỉ giả định aiCredits luôn đúng.
6. Kiểm tra số dòng affected: không có user phải trả lỗi, không báo grant thành công.
7. Không save lại entity User đã load trước bulk update. Cần clear/refresh/reload phù hợp rồi map response từ trạng thái mới; lưu ý @Modifying(clearAutomatically=true) đang có.
8. Không cập nhật role để “bật premium”. Premium tiếp tục suy ra từ paid balance.
9. Với request admin bị retry do mạng, quyết định idempotency/audit của grant riêng nếu cần. Atomic increment ngăn lost update, không tự ngăn hai request grant giống nhau.

### 5.5. Vì sao chỉ sửa phép cộng trong Java chưa đủ?

```text
Balance = 100
Request A đọc 100, muốn cộng 100
Request B đọc 100, muốn cộng 100
A lưu 200
B lưu 200 -> đáng lẽ phải là 300
```

Đây là lost update: hai writer cùng tính trên bản đọc cũ. Arithmetic UPDATE trên balance hiện tại giải quyết cạnh tranh giữa các grant dùng đúng cơ chế đó.

Tuy nhiên, profile/login hoặc writer khác save toàn entity cũ vẫn có thể ghi đè credit. Đây là rủi ro liên quan cần test riêng và rà soát writer sau hotfix; chưa được tái hiện bằng DB test trong tài liệu này.

Không thêm @Version rồi cho rằng mọi bulk update tự tham gia optimistic locking. Phải thiết kế đồng nhất version/targeted updates cho tất cả writer liên quan.

### 5.6. Phạm vi và kiểm chứng

Ảnh hưởng: adminGrantCredits, User/Credit service và UserRepository nếu cần, test credit/admin. Không cần đổi API amount hay schema để xử lý phần lẻ. Phân biệt rõ **grant thêm** với **admin set số dư tuyệt đối**; không thay toàn bộ setWholeAiCredits mà chưa kiểm tra callers.

- [ ] Ma trận units 0/25/50/75/100/125/250 cộng amount 1 và nhiều credit.
- [ ] Legacy row units=null vẫn cộng đúng bằng fallback.
- [ ] Legacy aiCredits stale không làm mất hoặc sinh thêm paid units.
- [ ] Hai grant đồng thời không mất phép cộng.
- [ ] Grant đồng thời payment/generate/swap giữ tổng before + grants - consumes = after.
- [ ] Profile/login writer không ghi đè credit mới; nếu chưa xử lý xong, issue phải ghi rõ residual risk.
- [ ] Invalid amount/user không tồn tại không thay balance.
- [ ] Response trả balance mới, không phải entity cache cũ.
- [ ] DB failure rollback đúng; premium vẫn dựa paid balance, không thay role.

## 6. Thứ tự xử lý đề xuất

Không tự triển khai theo tài liệu này. Khi được yêu cầu code, mỗi bước là PR nhỏ có regression tests riêng.

1. P0-01: sửa OTP transaction và kiểm chứng bằng DB thật cô lập.
2. P0-02: account-enabled policy cho REST và CONNECT; chốt thêm session WS revocation.
3. P0-03: destination authorization sau P0-02 vì cùng chạm JwtChannelInterceptor.
4. P0-04: public/shared DTO cùng cập nhật FE contract và kế hoạch thu hồi token có thể lộ.
5. P0-05: hotfix atomic admin grant; sau đó harden các User writers cạnh tranh.

Không gộp sửa OTP, thay toàn security config và refactor credit trong một PR. Privacy có thể ưu tiên triển khai sớm hoặc làm riêng nếu public API đang mở; các task không có một dependency kỹ thuật buộc phải chờ toàn bộ thứ tự trên.

## 7. Quyết định phải chốt trước khi code

- REST disabled response dùng status/code nào, public endpoint xử lý stale token ra sao?
- Account disable có cần ngắt WS ngay? Enable lại có cho token cũ hoạt động không?
- Có client/handler nào thật sự cần SEND qua /app không, hay WS chỉ dùng để nhận push?
- Public và shared được xem những trường nào? Ai quản lý đợt rotate token và thông báo link cũ?
- Admin grant cần idempotency key/audit đến mức nào? Có dữ liệu legacy aiCredits stale cần đối soát không?

## 8. Giới hạn kiểm chứng của tài liệu

- Đã đọc lại các đoạn code nêu bằng chứng và kiểm tra destinations ở frontend.
- Chỉ tạo tài liệu Markdown; không sửa code Java/TypeScript, config, schema hoặc tests.
- Không chạy lại bộ test nghiệp vụ trong lần viết tài liệu này.
- Các checkbox là tiêu chí cho lần implementation sau, không phải test đã pass.
- Mọi test có database/HTTP/WS phải dùng môi trường cô lập; không chạy thử trên dữ liệu người dùng hoặc thanh toán thật.
