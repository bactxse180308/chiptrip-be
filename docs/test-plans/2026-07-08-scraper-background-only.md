# Test Plan — Scraper "background-only" fix (2026-07-08)

> Tài liệu tự chứa cho agent (Codex) kiểm thử. KHÔNG cần ngữ cảnh chat trước đó.
> Ngôn ngữ dự án: tiếng Việt. Stack BE: Spring Boot 4 / Java 21 / MSSQL. Chạy lệnh trong thư mục `Chiptrip-be/`.

---

## 1. Thay đổi cần kiểm thử

**Mục tiêu của thay đổi:** khi tạo lịch trình (`POST /trips/generate`), địa điểm **Ngày 1** trước đây được enrich FULL *đồng bộ*; nếu SerpApi chết/hết quota + cache lạnh, Ngày 1 rơi vào sidecar scraper (~10–15s/điểm trên VPS 2 vCPU) → generate ~76s **> timeout 60s** của FE (trip vẫn lưu 201 nhưng user thấy lỗi).

**Fix:** scraper chuyển sang **background-only**. Thêm cờ `allowScraper` vào `PlaceEnrichmentService.resolvePlace`. Foreground bỏ scraper để trả nhanh; scraper enrich ở luồng nền cho *mọi* nhóm chưa enrich đủ, kể cả Ngày 1.

### Files đã đổi
| File | Nội dung |
|---|---|
| `module/place/service/PlaceEnrichmentService.java` | Thêm overload `resolvePlace(..., boolean forceRefresh, boolean allowScraper)`. Các overload cũ (5/6/7 tham số) default `allowScraper=true`. |
| `module/place/service/impl/PlaceEnrichmentServiceImpl.java` | Thread `allowScraper` qua `resolvePlace` → `enrichWithSerpApi`. 3 chỗ rơi về scraper: nếu `allowScraper=false` trả `SerpEnrichOutcome(false, null)` thay vì gọi `enrichWithScraper`. |
| `module/trip/service/impl/TripServiceImpl.java` | `resolveAllPlacesParallel`: foreground gọi `resolvePlace(..., false, false)`; danh sách enrich nền (`bgList`) dựng **sau join** = nhóm BASIC (ngày 2..N) **HOẶC** nhóm FULL có `serpEnriched=false`. |
| `module/place/service/impl/PlaceEnrichmentServiceImplScraperTest.java` | **ĐÃ CÓ** 2 unit test (đừng viết trùng — xem mục 3.1). |

### Bất biến quan trọng (đọc trước khi test)
- **Overload cũ `allowScraper=true`** → `ActivityAlternativeServiceImpl` (suggest-alternative) và `TripServiceImpl.enrichOnce` (retry-pass nền) **giữ nguyên hành vi**: vẫn dùng scraper.
- Chỉ **foreground của generate** truyền `allowScraper=false`.
- Nhóm FULL geocode fail (`resolvePlace` trả empty) **KHÔNG** đưa vào nền (thường do query xấu).
- Cache `place_cache` TTL 30 ngày là lá chắn chính; scraper chỉ chạy khi cache-miss + SerpApi miss.

---

## 2. Tiêu chí thành công (Definition of Done)
1. Toàn bộ test suite module PASS (`mvnw test`), không hồi quy.
2. Foreground generate **không bao giờ** gọi scraper (kể cả khi SerpApi miss).
3. Khi SerpApi miss, nhóm Ngày 1 + khách sạn **được đưa vào danh sách enrich nền**.
4. Khi SerpApi khỏe, nhóm FULL **không** vào nền (hành vi cũ, không phát sinh việc nền thừa).
5. Suggest-alternative vẫn dùng scraper (không bị fix làm hỏng).
6. (E2E VPS) Với SerpApi tắt + cache lạnh: generate trả **< 60s**; review/ảnh nảy ra dần sau đó.

---

## 3. Test tự động

### 3.1. Đã có — chỉ cần chạy lại, ĐỪNG viết trùng
File `PlaceEnrichmentServiceImplScraperTest.java`:
- `resolvePlace_allowScraperFalse_serpMiss_doesNotCallScraper` — `allowScraper=false` + SerpApi miss → `mapsScraperClient.scrape` KHÔNG được gọi; kết quả present, `serpEnriched=false`.
- `resolvePlace_defaultAllowsScraper_serpMiss_fallsBackToScraper` — overload 7 tham số (default `allowScraper=true`) → scraper ĐƯỢC gọi.

Chạy: `mvnw -Dtest=PlaceEnrichmentServiceImplScraperTest test` → kỳ vọng `Tests run: 2, Failures: 0`.

### 3.2. Cần BỔ SUNG — `PlaceEnrichmentServiceImpl` (unit, Mockito)
Cùng package `...place.service.impl` (class package-private). Dùng `@ExtendWith(MockitoExtension.class)`, `@MockitoSettings(LENIENT)`, `@InjectMocks`. Mock: `PlaceCacheService`, `PlaceCacheRepository`, `GoongClient`, `SerpApiClient`, `MapsScraperClient`, `ObjectMapper`.

| # | Kịch bản | Setup | Kỳ vọng |
|---|---|---|---|
| A | `allowScraper=false`, **preferSerp=true** (FOOD/ATTRACTION), SerpApi-first miss | `serpApiClient.searchPlaceCandidates` → empty; Goong trả GPS khớp vùng; gọi `resolvePlace(..., preferSerpIdentity=true, FULL, false, false)` | `scrape` KHÔNG gọi; `serpEnriched=false` (chốt nhánh preferSerp cũng bị gate) |
| B | `allowScraper=false`, SerpApi **có candidate khớp** | `searchPlaceCandidates` → 1 candidate khớp destination + có GPS + rating/photos | `serpEnriched=true`; `scrape` KHÔNG gọi (SerpApi đủ, không cần scraper) — chứng minh case khỏe không đổi |
| C | `allowScraper=true`, **preferSerp=true**, SerpApi-first miss | như A nhưng `allowScraper=true` (overload 8 tham số) | `scrape` ĐƯỢC gọi với `background=false` |
| D | `forceRefresh=true` vẫn tôn trọng `allowScraper=false` | gọi `resolvePlace(..., forceRefresh=true, allowScraper=false)` với SerpApi miss | `scrape` KHÔNG gọi |

> Ghi chú mock GeocodeResult: `new GoongClient.GeocodeResult(placeId, formattedAddress, lat, lng, provinceName, communeName)`. Để `placeId=null` để bỏ qua nhánh dedup-by-goongPlaceId. `formattedAddress` phải chứa destination (vd chứa "Hà Nội") để qua `matchesRegion`, tránh rơi nhầm sang `resolvePlaceViaSerpApi`.

### 3.3. Cần BỔ SUNG — `TripServiceImpl.resolveAllPlacesParallel` (integration-nhẹ, Mockito)
Đây là phần **quan trọng nhất & rủi ro nhất** (logic dựng `bgList` sau join). Class package-private → test cùng package `...trip.service.impl`.

**Chiến lược:** mock 2 executor chạy **đồng bộ** để quan sát được luồng nền:
```java
when(enrichmentExecutor.execute(any())) ...           // hoặc dùng Runnable::run
// backgroundEnrichmentExecutor: doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
```
Mock `placeEnrichmentService.resolvePlace(...)` trả `PlaceCache` với `serpEnriched` tùy input; verify các lần gọi.

Vì `resolveAllPlacesParallel`/`resolvePlaces` là private, gọi qua public `generateTrip` (mock `aiService.generateItinerary` trả itinerary cố định + mock `tripGenerationPersistenceService.persistGeneratedTrip`), HOẶC test gián tiếp qua ArgumentCaptor trên `backgroundEnrichmentExecutor.execute(Runnable)` rồi `.run()` và verify các call `resolvePlace(..., FULL, forceRefresh, allowScraper=true)`.

| # | Kịch bản | Itinerary dựng | Kỳ vọng |
|---|---|---|---|
| E | **Foreground luôn allowScraper=false** | itinerary ≥ 2 ngày | Mọi call `resolvePlace` ở foreground có tham số cuối `allowScraper=false`. Verify bằng ArgumentCaptor / `verify(...).resolvePlace(any,...,eq(false),eq(false))` |
| F | **SerpApi miss ngày 1 → nhóm ngày 1 vào nền** | ngày 1 có 2 activity FOOD; mock foreground resolvePlace trả cache `serpEnriched=false` | Background job re-resolve nhóm ngày 1 với `allowScraper=true` (default overload). Verify `resolvePlace` được gọi lại cho searchQuery ngày 1 ở luồng nền |
| G | **SerpApi khỏe → nhóm FULL KHÔNG vào nền** | ngày 1 mock trả `serpEnriched=true` | Background job KHÔNG chứa nhóm ngày 1 (chỉ ngày 2..N BASIC nếu có). Verify không có call nền cho searchQuery ngày 1 |
| H | **BASIC ngày 2..N luôn vào nền** (hồi quy) | ngày 2 có activity, mock trả cache bất kỳ | Background re-resolve nhóm ngày 2 (giữ hành vi cũ) |
| I | **Chỉ 1 ngày → không có nền thừa khi đủ enrich** | itinerary 1 ngày, mock serpEnriched=true | `backgroundEnrichmentExecutor.execute` KHÔNG được gọi (bgList rỗng → `buildBackgroundEnrichJob` trả null) |

> Nếu mock hóa `generateTrip` quá phức tạp, chấp nhận tối thiểu **E + F + G** (bao trọn tính đúng của cờ + logic bgList).

---

## 4. Test hồi quy (phải PASS, không được đổi hành vi)
1. `mvnw test` — toàn bộ 4 test class hiện có vẫn xanh: `PlaceQueryUtilTest`, `AiItineraryValidatorTest`, `GoongClientTest`, `TripGenerationPersistenceServiceImplTest` + test mới.
2. **Suggest-alternative**: kiểm tra `ActivityAlternativeServiceImpl` vẫn gọi `resolvePlace` overload 5 tham số (không có `allowScraper`) → mặc định dùng scraper. (Đọc code xác nhận; nếu có test cho service này thì chạy.)
3. **Không đổi khi SerpApi khỏe**: case B + G ở trên là chốt hồi quy chính.

---

## 5. Cách chạy

```bash
# Chỉ test mới (nhanh)
mvnw -Dtest=PlaceEnrichmentServiceImplScraperTest test

# Full suite module (unit *Test — KHÔNG cần Docker)
mvnw test

# Nếu có integration *IT (Testcontainers MSSQL) — CẦN Docker chạy
mvnw verify
```
- `mvnw test` chỉ chạy `*Test` (surefire) — 4 class hiện tại đều là `*Test`, không cần Docker.
- Cảnh báo "Mockito is currently self-attaching..." / "Java agent loaded dynamically" là **vô hại**, bỏ qua.
- Đọc kết quả từ `target/surefire-reports/*.txt` (dòng `Tests run: X, Failures: Y, Errors: Z`).

---

## 6. Test thủ công / E2E trên VPS (xác minh perf thật — phần fix nhắm tới)
> Chỉ làm khi có quyền VPS. BE = `/opt/app2` (port 8081/8081→api.chiptrip.online). Sidecar = `/opt/scraper` systemd `chiptrip-scraper.service` (port 8899).

**Kịch bản mô phỏng SerpApi chết (đúng ca gây bug):**
1. Bật scraper: `SCRAPER_ENABLED=true` trong `/opt/app2/.env`; đảm bảo `chiptrip-scraper.service` đang chạy (`systemctl status chiptrip-scraper`, `curl localhost:8899/health`).
2. Làm SerpApi "miss": tạm set `SERPAPI_API_KEY` thành key rác (hoặc rỗng) để mọi call SerpApi trả 401/miss. Restart BE.
3. Dùng **destination mới lạ** (để cache lạnh, vd một thị trấn ít gặp) → `POST /trips/generate` (3 ngày).
4. **Đo:**
   - [ ] Response trả **< 60s** (kỳ vọng ~25–35s), HTTP 201, `GET /trips/{id}` có đủ hoạt động + toạ độ ngày 1.
   - [ ] Ngay sau generate: `GET /trips/{id}` có `enriching=true`.
   - [ ] Sau 30–90s poll lại: ảnh/rating/review của **ngày 1** nảy ra dần; `enriching` cuối cùng về `false`.
   - [ ] Log BE: thấy `Background FULL enrich start: N place(s)` và các dòng `Scraper enrich: ...`; **KHÔNG** thấy scraper enrich trong pha đồng bộ trước khi trả response.
5. **Case SerpApi khỏe (hồi quy):** khôi phục key SerpApi thật → generate destination phổ biến → thời gian ~20–45s như cũ, background list nhỏ (chỉ ngày 2..N), không có scraper cho ngày 1.
6. Khôi phục: trả `SERPAPI_API_KEY` về key thật (nếu bước 2 đã đổi), restart BE.

**Tắt nhanh nếu có sự cố:** `SCRAPER_ENABLED=false` + restart BE → về Goong+SerpApi thuần.

---

## 7. Rủi ro cần soi kỹ khi review kết quả
- **Nền chạy nhiều hơn ở ca degraded:** khi SerpApi chết, bgList giờ gồm cả ngày 1 → `backgroundEnrichmentExecutor` (1 thread) làm nhiều việc hơn. Xác nhận không kẹt/không tràn queue (log `Background enrich không thể submit`).
- **Double-enrich:** ngày 1 đã resolve ở foreground rồi lại resolve ở nền — xác nhận cùng row `place_cache` được cập nhật (Activity trỏ đúng `placeCacheId`), không tạo row trùng.
- **Khách sạn:** giá/booking (`enrichAccommodation`) khoá ở foreground; nền chỉ thêm rating/ảnh/review — xác nhận `costVnd`/`bookingUrl` KHÔNG bị nền ghi đè sai.
- **`enriching` flag không kẹt true:** cửa sổ grace 5 phút (`ENRICHING_GRACE`) phải cho cờ tự tắt kể cả khi 1 điểm không bao giờ đủ.
```
