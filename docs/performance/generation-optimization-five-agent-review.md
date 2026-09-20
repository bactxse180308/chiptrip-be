# Tối ưu luồng tạo lịch trình: tổng hợp 5 sub-agent

Ngày: 20/09/2026. Trạng thái: đề xuất dựa trên working tree hiện tại, chưa triển khai hoặc benchmark. Không gọi provider trả phí, không đọc .env hay kết nối database ứng dụng. Các bản vá P0 và thay đổi có sẵn được giữ nguyên.

## 1. Thành phần và kết luận

| Agent | Vai trò | Kết quả |
|---|---|---|
| Cicero | Đề xuất A: AI, validation, retry | Deadline tổng, retry đúng loại lỗi, đo mọi attempt; sửa nội dung có mục tiêu là bước sau |
| Raman | Đề xuất B: địa điểm, cache, enrichment | Identity/tọa độ và thumbnail sẵn có trước; gallery/review nền; giữ quote khách sạn trước persist |
| Kierkegaard | Đề xuất C: frontend, status, reconnect | Khôi phục form/job, trạng thái trung thực, WS/poll thống nhất, durable idempotency |
| Carson | Phản biện correctness | Chấp nhận A lõi, điều chỉnh B, chia C theo giai đoạn; đặt gate credit/job/edit/quote |
| Schrodinger | Phản biện hiệu năng | Cùng chọn A thu gọn + C phục hồi trước, B sau; bắt buộc đo usable thay vì HTTP 202 |

Agent hiệu năng bị giới hạn sử dụng ở lượt đầu, đã tiếp tục và hoàn tất vòng phản biện sau. Hai agent phản biện được cung cấp cả ba đề xuất, không chỉ đánh giá riêng một nhánh.

**Khuyến nghị cuối: kết hợp A + B có kiểm soát + C.** Đây là phương án phù hợp nhất theo code/rủi ro đã đọc, chưa phải phương án được chứng minh nhanh nhất bằng thực nghiệm.

Mục tiêu: trả một lịch trình hợp lệ, đã commit, đọc và thao tác được sớm; dữ liệu bổ sung hoàn thiện sau. Không dùng progress giả, bỏ validation hoặc giảm chất lượng để làm số đo đẹp hơn.

## 2. Hiện trạng có bằng chứng

Đường dẫn dưới đây là từ root backend, trừ các mục ghi FE. Dòng là snapshot lúc review.

| Điểm cần xử lý | Bằng chứng | Ý nghĩa |
|---|---|---|
| Retry gửi lại cùng request body khi validation sai | `module/ai/service/impl/AiGenerateServiceImpl.java:54,108` | Chưa cung cấp lỗi để sửa bản nháp; có thể sinh lại toàn bộ |
| Timeout LLM 300 giây từng attempt, chưa có deadline tổng | `src/main/resources/application.yml:70`; `AiGenerateServiceImpl.java:450` | Timeout cấu hình không phải latency đo được; retry có thể cộng dồn |
| 429 đặt cooldown LLM chung rồi rotate key | `AiGenerateServiceImpl.java:418`; `common/resilience/ProviderResilience.java:38` | Key mới có thể vẫn bị chặn; phải xác định quota theo key/project/provider |
| Anchor geocode trước deadline place; ngày 1/khách sạn FULL | `module/trip/service/impl/TripServiceImpl.java:306,350,396` | 55/60 giây không bao trọn pha resolve; gallery/review nằm trên đường chờ |
| Foreground 3 worker/queue 50/CallerRuns; generation 8/100 | `common/config/AsyncConfig.java:15,37` | Quá tải có thể chạy task trên thread submit; tăng thread chưa chắc tăng thông lượng |
| BASIC cache có tọa độ nhưng freshness phụ thuộc serpSyncedAt | `module/place/service/impl/PlaceCacheServiceImpl.java:141` | Có thể tra lại provider dù identity còn dùng được |
| Quote khách sạn chốt vào Activity.costVnd khi persist | `module/trip/service/impl/TripGenerationPersistenceServiceImpl.java:216` | Không thể chuyển cả hotel enrichment xuống nền mà giữ nguyên semantics giá |
| Precheck cho paid lẻ đi tiếp nhờ trial | `TripServiceImpl.java:216`; `module/user/repository/UserRepository.java:58` | 25/50/75 units có thể tốn AI/maps rồi bị debit từ chối |
| Status RAM theo user, chưa durable idempotency | `TripGenerationStatusTracker.java:22`; `TripController.java:76` | Restart/mất response không có bảo đảm tìm lại cùng logical request |
| Marker thiếu form snapshot; poll có mismatch grace | FE `src/features/planning/trip-generation-state.ts:4`; `src/app/pages/Planning.tsx:179,443` | F5 rồi retry có thể thiếu input; UNKNOWN dễ bị coi là FAILED |
| Result ngừng poll sau 10 data updates dù còn enriching | FE `src/hooks/useApi.ts:30` | Bỏ lỡ WS có thể giữ dữ liệu cũ đến refetch khác |

Đường dẫn Java ngắn trong bảng nằm dưới `src/main/java/com/tranbac/chiptripbe/`. Các rủi ro concurrency là suy luận từ code, chưa được tái hiện trong lượt này.

## 3. Luồng đề xuất

```text
Người dùng bấm Tạo
  -> validate + precheck đúng chính sách credit
  -> nhận generationId, tiếp nhận có giới hạn
  -> AI tạo itinerary -> validate
  -> resolve identity/tọa độ trong budget chung
     + lấy thumbnail thật nếu đã có, không chờ thêm chỉ vì ảnh
     + quote/link khách sạn trong budget, fallback phải rõ nguồn
  -> transaction: trip graph + debit + enrichment intent
     + generation DONE/tripId khi durable job được triển khai
  -> commit
  -> WS báo hiệu hoặc GET status đối soát
  -> Result tải detail -> lịch trình dùng được
  -> gallery/review chạy nền -> cập nhật không reset ngày/scroll
```

Trong thiết kế cuối, durable job phải được lưu trước khi trả “đã tiếp nhận bền vững”; DONE và tripId cùng transaction với trip/credit. DB là nguồn trạng thái chuẩn, WS chỉ giúp biết kết quả sớm hơn. Giai đoạn chưa có durable job không được hứa khôi phục chắc chắn qua restart.

“Dùng được” nghĩa là đã có ngày, hoạt động, thời gian, dự toán và thao tác cần thiết; không phải vừa hiện spinner hoặc nhận 202. Nếu định vị chưa đủ, phải thông báo rõ, không tạo tọa độ giả để đạt chỉ tiêu.

## 4. Thứ tự triển khai đã qua phản biện

### Đợt 0: baseline và correctness

- Gắn correlation generationId qua các chặng, nhưng không log prompt cá nhân, token hoặc key.
- Đo queue wait, AI từng attempt, resolve, commit, GET detail và FE render usable. Ghi call/token của cả attempt thất bại; tách telemetry chi phí provider khỏi debit người dùng.
- Precheck khớp quy tắc: paid >= 100 hoặc paid == 0 và trial hợp lệ. Vẫn recheck/debit atomically lúc persist, không trừ ngay khi nhận request.
- Đo workload ngắn/dài, cache lạnh/ấm, tải thường/bão hòa trước khi chọn deadline hoặc số worker mới.

### Đợt 1: giảm chờ không kiểm soát và phục hồi UI

- Một deadline xuyên queue, token wait, anchor và HTTP; timeout từng call không vượt thời gian còn lại. Tách lỗi nội dung, timeout, quota và provider busy; retry hữu hạn, tôn trọng Retry-After và thêm jitter khi cần.
- Không nhả khóa/chuyển FAILED chỉ vì client hoặc future timeout trong khi worker vẫn có thể commit. Ngừng tạo công việc mới hết budget; xử lý kết quả về muộn bằng ownership/state kiểm tra được.
- Giới hạn tiếp nhận công việc, không để CallerRuns âm thầm kéo dài đường chờ. Reject/defer phải thể hiện rõ; intent nền phải được ghi cùng trip để tránh mất việc.
- FE lưu form snapshot theo user/generationId và version; tách generation state khỏi analytics. WS và poll cùng đi qua một reducer, không xử lý terminal hai lần.
- Poll có timeout riêng, backoff, kiểm tra ngay khi reconnect/focus. Mất mạng/status không xác định là UNKNOWN/RECONNECTING, không tự rePOST hoặc báo chắc chắn đã thất bại.
- Result còn enriching phải có đường refetch lại; nếu dừng polling theo budget, có thao tác kiểm tra cập nhật. Không reset ngày đang xem/scroll khi dữ liệu bổ sung tới.

### Đợt 2: giảm công việc foreground

- Tách độ mới identity/tọa độ khỏi độ đầy đủ media. Reuse identity còn hợp lệ; không resolve lại chỉ vì thiếu review.
- Bổ sung single-flight theo query chuẩn hóa + vùng; sau khi có identity thì media theo provider place ID. Timeout một caller không hủy lookup chung mà caller khác còn cần.
- Gallery/review ngày 1 cũng chạy nền; thumbnail thật đã có trong cache/search vẫn được hiển thị ở response đầu. Không thêm call bắt buộc chỉ để đủ ảnh.
- Giữ hotel quote/link foreground trong budget ở đợt đầu. Quote phải phân biệt hotel, check-in/out, adults và tham số ảnh hưởng giá; không dùng cache/single-flight chỉ theo địa điểm.
- Trước khi mở rộng cập nhật nền: bảo vệ tọa độ/địa điểm người dùng sửa bằng revision/ownership phù hợp; kiểm tra activity/trip đã xóa. Điều kiện searchQuery đơn lẻ chưa đủ.
- Giữ intent/claim/sweeper, phân loại FOUND/NOT_FOUND/TEMPORARY_UNAVAILABLE/provider-disabled. Retry phần thiếu, có jitter và giới hạn; worker mất lease không được ghi kết quả của lease cũ.
- Provider rate limit và giới hạn số HTTP đang chạy là hai giới hạn khác nhau; tính cả foreground, background, sweeper và photo refresh. Ưu tiên foreground nhưng không bỏ đói nền.

### Đợt 3: generation bền vững và chống xử lý lại

- Lưu job cùng liên kết generationId -> tripId trong DB hiện có; không cần thêm Kafka/Redis chỉ để đạt mục tiêu này.
- Idempotency theo user + generationId + hash payload: request lặp trả cùng job/kết quả; ID cũ với payload khác bị conflict.
- Claim/lease có version hoặc fencing để worker cũ không persist sau khi mất quyền. DONE + trip + debit commit cùng nhau.
- Có cách khôi phục job đã nhận nhưng executor chưa chạy hoặc tiến trình đã restart. Cần migration, retention, ownership check và test recovery trước release.
- Đợt này là điều kiện BẮT BUỘC trước auto-rePOST hoặc cam kết resume qua restart. Có thể thiết kế song song từ đợt 1; không phải phần tùy chọn của bảo đảm đó.
- Hoãn cancel thật tới khi có phân xử nguyên tử cancel/commit. Rời màn hình hoặc abort HTTP không có nghĩa hủy worker hay hoàn phí.

## 5. Những hướng chưa chọn

- Không tăng thread hàng loạt: downstream có rate gate, queue và quota chung.
- Không đổi model hay giới hạn token thấp ngay: chưa đo chất lượng và tỷ lệ output bị cắt.
- Chưa làm partial repair/phân mảnh itinerary: tăng độ phức tạp ghép ngày/ngân sách/logistics; chỉ thử khi metrics cho thấy validation retry chiếm phần đáng kể.
- Không stream JSON chưa validate/persist như kết quả chính thức; chỉ phase thật được dùng để báo tiến độ.
- Không chuyển giá khách sạn xuống nền rồi tự ghi đè cost người dùng đã sửa.
- Không đổi key nhằm vượt hạn mức chung; phạm vi cooldown phải dựa vào semantics quota thực tế.

## 6. Benchmark và gate phát hành

Harness cô lập, dữ liệu tổng hợp, fake HTTP providers và chặn outbound ngoài fixture. DB transaction cần SQL Server disposable; fake repository không chứng minh rollback hoặc p95 DB thật.

| Kịch bản | Điều kiện đạt |
|---|---|
| Cache lạnh/ấm, trip ngắn/dài, địa điểm trùng | So cùng input/chất lượng; đo calls, usable, enriched; kiểm tra identity/tọa độ/ảnh đúng |
| Tải thường, bão hòa, vượt queue | Không treo/mất job âm thầm; inflight và queue bounded; nhận/từ chối có trạng thái rõ |
| 429, 503, timeout, JSON/validation lỗi | Retry hữu hạn đúng scope; không bắt đầu provider call sau deadline; metrics bao gồm attempt lỗi |
| POST mất response, WS mất, F5, nhiều tab | Không tự tạo request mới khi UNKNOWN; restore đúng form; terminal được áp dụng một lần |
| Duplicate ID, cùng ID khác payload, crash | Một logical request tối đa một trip/debit; lấy lại được trip đã commit; conflict payload rõ |
| Credit 0/25/50/75/100/200 và trial qua ngày VN | Precheck khớp policy; atomic debit quyết định cuối; lỗi persistence rollback toàn graph/credit |
| Sửa/xóa activity khi provider đang chờ | Worker muộn không ghi đè sửa đổi hoặc tái tạo dữ liệu đã xóa |
| Cùng hotel khác ngày/adults | Không dùng lẫn quote/link; giá ước tính và giá provider có nguồn/trạng thái rõ |
| Lease hết hạn, worker cũ tiếp tục | Worker cũ không ghi đè terminal hoặc commit công việc sau mất quyền |

Hai thước đo chính:

- **Time-to-usable:** từ click đến Result render lịch trình đã commit và có thể thao tác.
- **Time-to-enriched:** từ click đến hoàn thành enrichment yêu cầu; báo riêng trạng thái không thể hoàn tất, không loại các job lỗi khỏi thống kê một cách im lặng.

Báo p50/p95 từng chặng và end-to-end, số mẫu, success/timeout rate, chất lượng, tổng attempts/token/calls. Không cộng p95 các chặng thành p95 tổng. So cùng tải/cấu hình/cache, tính cả overhead instrumentation; fake-provider benchmark chứng minh điều phối, không chứng minh độ trễ LLM production.

Chưa có số đo để cam kết “nhanh hơn X%” hoặc “dưới 30 giây”. Ngưỡng nghiệm thu định lượng sẽ chốt sau baseline; chất lượng, không trừ trùng và không mất dữ liệu là gate bắt buộc ngay từ đầu.

## 7. Phạm vi lượt này

Đã có đủ 3 đề xuất và 2 phản biện. Chỉ thêm tài liệu này; chưa thay logic generation, chưa chạy test/benchmark mới. Kết quả 149 test của đợt sửa P0 trước không phải bằng chứng phương án tối ưu đã được triển khai hoặc nhanh hơn.
