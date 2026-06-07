# PROMPT — Tối ưu hoá tích hợp Goong + SerpApi cho ChipTrip

> **Dành cho:** AI coding agent (Claude Code / Cursor / Copilot) thực thi trên repo `chiptrip-be` (và `chiptrip-fe`).
> **Đọc hết file này trước khi sửa bất kỳ dòng code nào.** Đây là prompt self-contained — mọi context cần thiết đã nằm trong đây.

---

## 0. SHARED CONTEXT — Dự án & stack

ChipTrip là **AI Travel Planner** cho thị trường Việt Nam. Backend Spring Boot 4.x (Java 21, MSSQL, MapStruct, Spring Security + JWT). Frontend React + TypeScript (Vite). Mobile Flutter.

Luồng tạo lịch trình hiện tại (đã hoạt động):

```
POST /api/v1/trips/generate
  → TripServiceImpl.generateTrip()        (orchestration, NOT @Transactional)
     → AiService.generateItinerary()       Gemini 2.5 Flash → JSON
     → resolvePlaces()                      với mỗi activity FOOD/ATTRACTION/ACCOMMODATION:
         → PlaceEnrichmentService.resolvePlace(searchQuery, destination)
             → GoongClient.forwardGeocode() lấy lat/lng         ← ĐANG DÙNG GOONG V1
             → SerpApiClient enrich          rating/ảnh/giờ/reviews
             → lưu PlaceCache
     → TripGenerationPersistenceService.persistGeneratedTrip()  lưu DB trong 1 tx
```

**Convention bắt buộc (mục 8 CONTEXT.md):**
- Package theo feature, layer Controller → Service → Repository.
- Không trả Entity ra API — luôn qua DTO.
- Constructor injection qua Lombok `@RequiredArgsConstructor`, field `private final`.
- API key đọc từ `@ConfigurationProperties`, KHÔNG hardcode.
- Response wrapper `ApiResponse<T>`.
- Tên địa điểm/PK là `Long` (KHÔNG phải UUID), DB là MSSQL (KHÔNG phải PostgreSQL), field user là `fullName` (KHÔNG phải `name`).
- Trả lời/comment bằng tiếng Việt, giữ thuật ngữ tiếng Anh.

---

## 1. VẤN ĐỀ HIỆN TẠI CỦA APP

Dựa trên thống kê usage Goong dashboard và đọc code, có **5 vấn đề** cần xử lý:

### 1.1. ❌ Autocomplete gần như không được dùng (chỉ 2 calls)
`GoongClient.autocomplete()` và endpoint `GET /api/v1/places/search` **đã tồn tại và đúng**, NHƯNG frontend `Planning.tsx` lại dùng **mảng hardcode** `regions`/`allPlaces` để filter gợi ý điểm đến thay vì gọi backend:

```typescript
// src/app/pages/Planning.tsx — CODE HIỆN TẠI (SAI)
const allPlaces = regions.flatMap(r => r.places);   // mảng cứng ~18 thành phố
const filteredDestSuggestions = destFocused && destination.length > 0
  ? allPlaces.filter(p => p.name.toLowerCase().includes(destination.toLowerCase()))
  : [];
```

**Hậu quả:** user chỉ chọn được trong ~18 thành phố hardcode; gõ "Mộc Châu", "Tà Xùa", "Bãi Cháy" → không ra gợi ý nào. Goong Autocomplete (kho dữ liệu cả nước) bị bỏ phí.

### 1.2. ❌ Place Detail = 0 calls (chưa implement)
`GoongClient` **không có** method `placeDetail()`. Hệ quả: khi user chọn 1 gợi ý autocomplete, không có cách lấy `lat/lng` chính xác từ `place_id`. Hiện tại app đang "lách" bằng Forward Geocode (xem 1.3) — kém chính xác hơn.

### 1.3. ⚠️ Forward Geocode đang dùng Goong V1 (192 calls)
`GoongClient.forwardGeocode()` gọi endpoint **V1**:

```java
.uri(b -> b.path("/geocode").queryParam("address", query)...)   // V1
```

V1 trả `formatted_address` dạng string thuần, **không có mã hành chính** (tỉnh/huyện/xã). `PlaceCache` vì thế không lưu được `province`/`district` chuẩn hóa → sau này muốn filter "chuyến đi theo tỉnh" hoặc thống kê theo vùng phải parse string địa chỉ (không đáng tin trong bối cảnh VN đang sáp nhập địa giới).

### 1.4. 🔴 Frontend gọi Goong trực tiếp + lộ API key (Direction 465 calls)
`src/lib/goong.ts` gọi Goong Direction & Distance Matrix **thẳng từ browser** với key lộ trong bundle:

```typescript
const GOONG_API_KEY = import.meta.env.VITE_GOONG_API_KEY;   // LỘ trong JS bundle
const url = `${GOONG_API_BASE}/Direction?...&api_key=${GOONG_API_KEY}`;
```

**Hậu quả:** (a) vi phạm nguyên tắc "Frontend không gọi API ngoài trực tiếp" trong CONTEXT.md mục 7.4; (b) ai mở DevTools cũng thấy key → bị lạm dụng quota; (c) không cache được → Direction phình lên 465 calls.

### 1.5. 🟡 SerpApi chưa khai thác hết
`SerpApiClient` đã enrich rating/ảnh/giờ/reviews rất tốt (engine `google_maps`, `google_maps_photos`, `google_maps_reviews`). Nhưng với activity `ACCOMMODATION`, app chỉ hiện tên khách sạn — chưa có **giá phòng + deeplink đặt phòng** (Google Hotels engine), là giá trị thực sự cho user.

---

## 2. CORRECTIONS / RÀNG BUỘC BẮT BUỘC

Đọc kỹ trước khi code. Vi phạm các điểm này = làm lại.

1. **KHÔNG xoá luồng V1 ngay.** Migrate Forward Geocode sang V2 phải **fail-soft**: nếu V2 lỗi/không có `compound`, vẫn lấy được lat/lng như cũ. App đang chạy production-like, không được làm vỡ luồng generate trip.

2. **KHÔNG tự bịa field name của response V2 `has_vnid`.** Goong cập nhật `has_vnid` tháng 03/2026; cấu trúc field chính xác (`compound.province` vs `compound.province_id`...) phải được **verify bằng 1 test call thật** rồi mới map. Xem Task 1, Bước 1.

3. **KHÔNG hardcode Goong/SerpApi key.** Mọi key qua `GoongProperties` / `SerpApiProperties`.

4. **KHÔNG để frontend giữ `VITE_GOONG_API_KEY` cho REST API.** Sau Task 3, các call Direction/Distance Matrix/Static Map phải proxy qua backend. (Maptiles token cho việc render bản đồ JS thì được giữ ở frontend — đó là key khác, có domain restriction.)

5. **Cache-aware:** mọi call Goong/SerpApi mới phải tôn trọng `PlaceCache` (đã có TTL + backoff). Không thêm call trùng lặp.

6. **Migration DB an toàn cho MSSQL:** thêm cột mới vào `place_cache` phải `NULL`-able (Hibernate `ddl-auto: update` sẽ tự thêm cột — không được đặt `nullable = false` cho cột mới trên bảng đã có data).

7. **Backward-compatible API:** không đổi shape response của các endpoint hiện có (`/places/search`, `/places/{id}`). Chỉ **thêm** field mới.

---

## 3. TASKS — Theo thứ tự ưu tiên

> Làm tuần tự. Sau mỗi Task có **Acceptance Criteria** + **Test checkpoint**. Không sang Task sau khi Task trước chưa pass.

---

### ✅ TASK 1 — Migrate Forward Geocode V1 → V2 + lưu mã hành chính (`has_vnid`)

**Mục tiêu:** `PlaceCache` lưu thêm tỉnh/huyện/xã (tên + mã) để sau này filter/thống kê theo vùng không cần parse string.

#### Bước 1 — Verify response V2 trước (BẮT BUỘC, làm đầu tiên)

Viết 1 test tạm (hoặc dùng `curl`) gọi đúng endpoint V2 với `has_vnid=true`, log raw JSON ra console:

```
GET https://rsapi.goong.io/v2/geocode?address=Phố cổ Hội An, Quảng Nam&has_vnid=true&api_key={GOONG_API_KEY}
```

Ghi lại **chính xác** tên các field hành chính trong response (ví dụ có thể là `compound.province`, `compound.district`, `compound.commune`, kèm `*_id` hoặc `*_code`). **Map theo field thật, không theo giả định trong prompt này.** Sau khi xác định xong, xoá test tạm.

> Nếu V2 endpoint trả lỗi với account hiện tại (một số tính năng V2 cần plan trả phí): ghi log rõ ràng, giữ nguyên V1 cho lat/lng, và để phần administrative-code parse là no-op (cột mới = null). Báo lại trong phần OUTPUT để Bắc quyết định nâng plan.

#### Bước 2 — Mở rộng `PlaceCache` entity

Thêm các cột mới (tất cả `NULL`-able). Tên field map theo response thật ở Bước 1:

```java
// src/main/java/com/tranbac/chiptripbe/module/place/entity/PlaceCache.java

/** Tên tỉnh/thành từ Goong V2 compound (vd "Quảng Nam") */
@Nationalized
@Column(name = "province_name", length = 100)
private String provinceName;

/** Mã tỉnh/thành theo đơn vị hành chính VN (has_vnid) */
@Column(name = "province_code", length = 20)
private String provinceCode;

/** Tên quận/huyện (vd "Hội An") */
@Nationalized
@Column(name = "district_name", length = 100)
private String districtName;

@Column(name = "district_code", length = 20)
private String districtCode;

/** Tên phường/xã */
@Nationalized
@Column(name = "ward_name", length = 100)
private String wardName;

@Column(name = "ward_code", length = 20)
private String wardCode;
```

> Dùng `@Nationalized` cho các field tên (tiếng Việt có dấu) — nhất quán với `name`/`address` hiện có. Mã (`*_code`) là ASCII nên không cần.

#### Bước 3 — Sửa `GoongClient.forwardGeocode()` sang V2

- Đổi path `/geocode` → `/v2/geocode`, thêm `has_vnid=true`.
- Mở rộng record `GeocodeResult` để mang theo administrative info:

```java
public record GeocodeResult(
        String placeId,
        String formattedAddress,
        BigDecimal lat,
        BigDecimal lng,
        // ── MỚI: administrative codes từ has_vnid (nullable) ──
        String provinceName, String provinceCode,
        String districtName, String districtCode,
        String wardName,     String wardCode
) {}
```

- Trong `parseGeocodeResult()`: parse thêm khối `compound` (theo field thật ở Bước 1). Nếu không có `compound` → set các field admin = `null` (fail-soft, vẫn trả lat/lng).
- **Giữ nguyên** logic parse `lat/lng`/`place_id`/`formatted_address` — chỉ THÊM, không sửa phần cũ.

> ⚠️ Vì record có thêm field, mọi nơi `new GeocodeResult(...)` sẽ lỗi compile. Tìm hết và sửa (chủ yếu trong `GoongClient` + có thể test). Dùng giá trị `null` cho field admin khi chưa parse được.

#### Bước 4 — Lưu admin codes vào `PlaceCache`

Trong `PlaceEnrichmentServiceImpl.resolvePlace()`, đoạn set field lên `toSave` (sau `setAddress`, trước SerpApi enrich), thêm:

```java
toSave.setProvinceName(geo.provinceName());
toSave.setProvinceCode(geo.provinceCode());
toSave.setDistrictName(geo.districtName());
toSave.setDistrictCode(geo.districtCode());
toSave.setWardName(geo.wardName());
toSave.setWardCode(geo.wardCode());
```

#### Bước 5 — Cập nhật config (nếu cần param mặc định)

Trong `GoongProperties` cân nhắc thêm flag `geocodeV2: true` để bật/tắt dễ dàng (optional, default true). `application.yml`:

```yaml
app:
  goong:
    api-key: ${GOONG_API_KEY}
    base-url: https://rsapi.goong.io
    timeout-seconds: 5
    geocode-v2: true          # MỚI — dùng /v2/geocode + has_vnid
```

#### ✅ Acceptance Criteria — Task 1
- [ ] App khởi động, Hibernate tự thêm 6 cột mới vào `place_cache` (kiểm tra MSSQL).
- [ ] Tạo 1 trip mới với điểm đến rõ ràng (vd Đà Lạt) → query `place_cache` thấy `province_name`/`district_name` được điền cho các activity geocode thành công.
- [ ] Nếu V2 trả lỗi → log cảnh báo, lat/lng vẫn có, trip vẫn tạo được (không 500).
- [ ] Không có chỗ nào hardcode key.

#### 🧪 Test checkpoint — Task 1
- Unit test `GoongClient.parseGeocodeResult()` với 2 mock JSON: (a) có `compound`, (b) không có `compound` → đảm bảo cả 2 đều trả lat/lng, case (a) có thêm admin, case (b) admin = null.
- Tên test theo convention: `GoongClientTest.java`.

---

### ✅ TASK 2 — Thêm Goong Place Detail + sửa luồng Autocomplete frontend

**Mục tiêu:** input "Điểm đến" gọi Goong Autocomplete thật; chọn xong lấy lat/lng qua Place Detail. Giải quyết vấn đề 1.1 + 1.2.

#### Bước 1 — Backend: thêm `GoongClient.placeDetail()`

```java
/**
 * Lấy chi tiết địa điểm từ place_id (sau khi user chọn 1 autocomplete prediction).
 * Endpoint: GET /Place/Detail?place_id=...&api_key=...
 */
public Optional<GeocodeResult> placeDetail(String placeId) {
    // tương tự forwardGeocode: gọi /Place/Detail, parse result.geometry.location
    // có thể tái dùng parseGeocodeResult() nếu cấu trúc "result" tương đồng
}
```

#### Bước 2 — Backend: mở rộng `/places/search` trả kèm `place_id`

Endpoint `GET /api/v1/places/search?q=` hiện trả `PlaceSearchResponse` với `description/mainText/secondaryText`. **Thêm** `placeId` vào mỗi prediction (đã có sẵn trong `AutocompleteResult.placeId` — chỉ cần map ra DTO).

Thêm endpoint mới lấy detail:
```
GET /api/v1/places/detail?placeId=...   → trả { lat, lng, formattedAddress, provinceName, ... }
```

> Cả 2 endpoint đặt trong `ExternalController` (hoặc tách `PlaceLookupController` nếu gọn hơn). Auth `bearerAuth` như các endpoint khác.

#### Bước 3 — Frontend: thay mảng hardcode bằng gọi API

Trong `Planning.tsx`:
- Tạo hook `usePlaceAutocomplete(query)` dùng TanStack Query, **debounce 300ms**, gọi `GET /places/search?q=`.
- Bỏ `allPlaces.filter(...)` cho ô điểm đến/điểm đi; thay bằng kết quả từ hook.
- Khi user chọn 1 prediction → gọi `/places/detail?placeId=` để lấy lat/lng, lưu vào state (dùng cho generate trip sau này).
- Giữ `regions` hardcode chỉ để hiển thị "gợi ý nhanh" khi ô input còn trống (UX), không dùng làm nguồn search chính.

> Không gọi axios trực tiếp trong component — qua TanStack Query (CONTEXT.md mục 8). Type đầy đủ, không `any`.

#### ✅ Acceptance Criteria — Task 2
- [ ] Gõ "Mộc Châu" / "Tà Xùa" trong ô điểm đến → ra gợi ý thật từ Goong (Goong dashboard: Autocomplete count tăng).
- [ ] Chọn 1 gợi ý → Place Detail được gọi (Place Detail count tăng từ 0).
- [ ] Debounce hoạt động: gõ nhanh "Đà Nẵng" KHÔNG bắn 7 request (kiểm tra Network tab — chỉ 1-2 request sau khi ngừng gõ).
- [ ] Response `/places/search` vẫn backward-compatible (chỉ thêm `placeId`).

#### 🧪 Test checkpoint — Task 2
- Unit test `GoongClient.placeDetail()` với mock JSON.
- Frontend: kiểm tra debounce thủ công bằng Network tab.

---

### ✅ TASK 3 — Proxy Direction/Distance Matrix/Static Map qua backend (bảo mật + cache)

**Mục tiêu:** thu hồi API key khỏi frontend bundle, cache lại để giảm Direction count. Giải quyết vấn đề 1.4.

#### Bước 1 — Backend: thêm method vào `GoongClient`

```java
public record DirectionResult(int distanceMeters, int durationSeconds, String overviewPolyline) {}

/** GET /Direction?origin=lat,lng&destination=lat,lng&vehicle=... */
public Optional<DirectionResult> direction(double oLat, double oLng,
                                           double dLat, double dLng, String vehicle) { ... }

/** GET /DistanceMatrix?origins=...&destinations=...&vehicle=... */
public List<TravelSegment> distanceMatrix(...) { ... }
```

#### Bước 2 — Backend: endpoint proxy

```
GET /api/v1/routes/direction?oLat=&oLng=&dLat=&dLng=&vehicle=car
GET /api/v1/routes/static-map?center=lat,lng&zoom=13&width=600&height=300&markers=...
```

- Static Map: backend gọi `GET https://rsapi.goong.io/staticmap?...&api_key=` rồi **stream `byte[]` ảnh PNG về** (giống pattern PDF export đã định trong dự án), HOẶC trả URL đã ký nếu muốn FE render `<img>` (nhưng URL có key → KHÔNG nên; ưu tiên stream byte hoặc lưu ảnh lên R2 rồi trả public URL).
- Cân nhắc cache Direction theo cặp (origin, destination, vehicle) trong bảng nhỏ hoặc in-memory TTL — vì route giữa 2 activity cố định không đổi.

#### Bước 3 — Frontend: bỏ key, gọi backend

- `src/lib/goong.ts`: đổi `getDirection`/`getConsecutiveTravelTimes` sang gọi `/api/v1/routes/*` qua axios client (có JWT interceptor). **Xoá `GOONG_API_KEY` khỏi file này.**
- Giữ Maptiles token (render bản đồ JS) ở FE — đó là key khác, set domain restriction trên Goong dashboard.

#### ✅ Acceptance Criteria — Task 3
- [ ] Mở DevTools → Network: KHÔNG còn request nào tới `rsapi.goong.io` từ browser (trừ maptiles).
- [ ] `VITE_GOONG_API_KEY` không còn xuất hiện trong JS bundle (search bundle sau `npm run build`).
- [ ] Thumbnail bản đồ + lộ trình vẫn hiển thị đúng (qua backend).
- [ ] Direction được cache: load lại cùng 1 trip không tăng Direction count tuyến tính.

#### 🧪 Test checkpoint — Task 3
- Integration test endpoint `/routes/direction` với GoongClient mock.

---

### ✅ TASK 4 — Mở rộng SerpApi: giá phòng khách sạn (Google Hotels)

**Mục tiêu:** activity `ACCOMMODATION` có giá phòng + deeplink. Giải quyết vấn đề 1.5.

#### Bước 1 — `SerpApiClient.searchHotels()`

```java
/**
 * GET /search?engine=google_hotels&q=...&check_in_date=&check_out_date=&currency=VND&gl=vn&hl=vi
 * Trả về: tên KS, giá/đêm (VND), rating, ảnh, link.
 */
public Optional<HotelData> searchHotel(String name, LocalDate checkIn, LocalDate checkOut) { ... }

public record HotelData(String name, Long pricePerNightVnd, BigDecimal rating,
                        String thumbnailUrl, String bookingLink) {}
```

> `currency=VND` để giá trả về thẳng VNĐ — nhất quán với toàn app. `check_in/check_out` lấy từ `Trip.dateStart/dateEnd` của ngày tương ứng.

#### Bước 2 — Tích hợp vào enrichment

Trong `PlaceEnrichmentServiceImpl`, khi activity là `ACCOMMODATION` (hoặc thêm tham số `ActivityType` vào `resolvePlace`): gọi `searchHotels()`, set `bookingUrl` + lưu giá vào `PlaceCache` (thêm cột `price_per_night_vnd` nullable nếu muốn cache).

> Best-effort như SerpApi hiện tại: fail → vẫn giữ data Goong, không vỡ luồng.

#### ✅ Acceptance Criteria — Task 4
- [ ] Trip có activity ACCOMMODATION → có `bookingUrl` + giá phòng hiển thị.
- [ ] Currency là VNĐ.
- [ ] SerpApi Hotels fail → activity vẫn tạo bình thường (chỉ thiếu giá).

#### 🧪 Test checkpoint — Task 4
- Unit test `SerpApiClient.searchHotel()` với mock JSON Google Hotels.

---

## 4. CONVENTIONS — Nhắc lại nhanh khi code

- **Java:** `@Service`/`@Component` + `@RequiredArgsConstructor`, field `private final`. Không bắt `Exception` chung — bắt cụ thể, log `log.warn(...)` rồi fail-soft (return `Optional.empty()`/`null`).
- **WebClient:** tái dùng pattern `@PostConstruct init()` + `.timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))` đã có trong `GoongClient`/`SerpApiClient`.
- **DTO:** không trả entity. Endpoint mới → tạo response DTO + map (MapStruct hoặc builder thủ công như `PlaceController` đang làm).
- **Frontend:** TanStack Query cho mọi fetch, debounce input search, type đầy đủ (PascalCase, không prefix `I`, không `any`).
- **Commit:** Conventional Commits, mỗi Task ≥ 1 commit. VD: `feat(geocoding): migrate Goong forward geocode to V2 with has_vnid`.

---

## 5. ĐỪNG LÀM (out of scope)

- ❌ Đừng đổi sang Google Maps API (Goong cover đủ VN, Google chặn billing VN).
- ❌ Đừng thêm Google Flights / Images ở lần này — để Phase sau. Chỉ làm Hotels (Task 4).
- ❌ Đừng refactor toàn bộ `PlaceEnrichmentService` — chỉ thêm/sửa đúng phần cần.
- ❌ Đừng đổi schema response của endpoint cũ (chỉ thêm field).
- ❌ Đừng tự nâng cấp version dependency nếu không cần.

---

## 6. OUTPUT FORMAT — Báo cáo sau khi xong

Khi hoàn thành (hoặc tới checkpoint), báo cáo theo mẫu:

```
## Đã làm
- Task 1: [tóm tắt] — files: GoongClient.java, PlaceCache.java, PlaceEnrichmentServiceImpl.java, ...
- Task 2: ...

## Verify response V2 has_vnid (Task 1, Bước 1)
- Endpoint test: [URL]
- Field hành chính thật trong response: compound.{tên field thật}
- [Nếu V2 lỗi với account hiện tại] → cần nâng plan Goong? [có/không, lý do]

## DB migration
- Cột mới thêm vào place_cache: [liệt kê]
- Đã verify trên MSSQL: [có/không]

## Test
- Unit tests added: [liệt kê], pass: [có/không]
- Acceptance criteria từng task: [tick]

## Cần Bắc quyết định
- [Vấn đề mở, nếu có — vd: cách lưu Static Map (stream byte vs R2), nâng plan Goong V2, ...]

## Goong dashboard sau khi test (kỳ vọng)
- Autocomplete: tăng (từ 2)
- Place Detail: tăng (từ 0)
- Direction từ frontend: 0 (đã proxy qua backend)
```

---

## 7. THỨ TỰ THỰC THI ĐỀ XUẤT

```
Task 1 (V2 + has_vnid)         ← CORE, làm trước, độc lập
   ↓
Task 2 (Autocomplete + Detail) ← fix luồng input, giá trị UX cao nhất
   ↓
Task 3 (proxy + bảo mật)       ← thu hồi key, giảm Direction count
   ↓
Task 4 (Hotels)                ← tính năng mới, làm sau cùng
```

Mỗi Task pass Acceptance Criteria + Test checkpoint mới sang Task kế. Nếu bí ở Bước verify V2 (Task 1 Bước 1), dừng lại hỏi Bắc thay vì đoán field name.
