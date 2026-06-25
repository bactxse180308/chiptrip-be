# PRD — MEMBER sửa lịch trình cần OWNER duyệt (Change Proposal Workflow)

Status: ready-for-human
Created: 2026-06-19
Owner: (dev)

> Quyết định scope: tách thành **feature riêng**, làm sau. Hiện tại CRUD/swap/reorder activity vẫn **owner-only** (an toàn). File này là kế hoạch để triển khai khi sẵn sàng.

---

## 1. Mục tiêu

Cho phép **thành viên được mời** (`TripMember` role `MEMBER`) đề xuất chỉnh sửa lịch trình; thay đổi **chỉ áp dụng sau khi OWNER duyệt**. Giữ nguyên trải nghiệm owner (owner sửa trực tiếp, không qua duyệt).

### Non-goals
- Không cho MEMBER áp dụng thay đổi trực tiếp.
- Không làm real-time collaborative editing.
- Không mở quyền cho guest (TripMember.user == null).

---

## 2. Hiện trạng (căn cứ code)

- Mọi thao tác activity scope theo owner: `ActivityServiceImpl.findTripAndValidateOwnership` (`trip.user.id == userId`), `ActivityAlternativeServiceImpl` dùng `findByIdAndUserId*`. MEMBER không qua được các guard này.
- `TripMember` đã có (`trip`, `user`, `role` enum `TripMemberRole`), seed OWNER khi tạo trip (`TripMemberService.seedOwner`).
- Notification đã có hạ tầng event/listener (`module/notification`), thêm type là pattern quen thuộc.

---

## 3. Phạm vi hành động cần duyệt (đề xuất v1)

| Action | v1 | Ghi chú |
|---|---|---|
| UPDATE activity | ✅ | sửa name/time/cost/desc... |
| SWAP activity (AI) | ✅ | tốn credit → **OWNER trả** khi duyệt |
| DELETE activity | ✅ | |
| ADD activity | ⏳ v2 | |
| REORDER | ⏳ v2 | ít rủi ro, có thể cho MEMBER làm thẳng sau |

---

## 4. Data model

Entity mới `TripChangeProposal` (table `trip_change_proposals`):

| Field | Kiểu | Ghi chú |
|---|---|---|
| id | Long PK | |
| tripId | Long | (Long thuần, cleanup tay khi xóa trip — theo pattern social) |
| proposedByUserId | Long | MEMBER đề xuất |
| action | enum `ProposalAction` | UPDATE / SWAP / DELETE |
| dayId | Long | |
| activityId | Long (nullable) | null nếu ADD (v2) |
| payloadJson | NVARCHAR(MAX) | dữ liệu thay đổi (UpdateActivityRequest serialize / sessionId+optionId cho SWAP) |
| status | enum `ProposalStatus` | PENDING / APPROVED / REJECTED / EXPIRED |
| decidedByUserId | Long (nullable) | OWNER |
| decidedAt | LocalDateTime (nullable) | |
| createdAt | LocalDateTime | |

Index: `(trip_id, status)`, `(proposed_by_user_id)`.

---

## 5. API

| Method | Endpoint | Role | Mục đích |
|---|---|---|---|
| POST | `/trips/{tripId}/proposals` | MEMBER | Gửi đề xuất `{action, dayId, activityId?, payload}` → PENDING |
| GET | `/trips/{tripId}/proposals?status=` | OWNER + MEMBER | OWNER xem tất cả; MEMBER xem của mình |
| PATCH | `/trips/{tripId}/proposals/{pid}` | OWNER | `{decision: APPROVE\|REJECT}` — APPROVE mới thực thi thay đổi thật |
| DELETE | `/trips/{tripId}/proposals/{pid}` | MEMBER (author) | rút đề xuất khi còn PENDING |

- Khi APPROVE: gọi lại đúng service hiện có (`ActivityService.updateActivity` / `deleteActivity`, `ActivityAlternativeService.replaceActivity`) trong 1 transaction; set proposal APPLIED/APPROVED.
- Phân quyền: thêm helper `requireTripMember(tripId, userId)` (OWNER hoặc MEMBER) cho bước đề xuất; `requireOwner` cho bước duyệt.

---

## 6. Credit khi SWAP qua đề xuất

- **OWNER trả credit** lúc APPROVE (nhất quán: ai sở hữu trip thì chịu chi phí). Free-swap quota của trip vẫn áp dụng như swap thường.
- Lưu ý vòng đời session gợi ý: `ActivityAlternativeSession` TTL 15'. Nếu OWNER duyệt muộn hơn 15' → session hết hạn → cần regenerate. **Quyết định cần chốt**: (a) nới TTL cho proposal, hay (b) lưu snapshot option vào payload và bỏ phụ thuộc session khi APPROVE.

---

## 7. Notification

- Type mới: `TRIP_CHANGE_PROPOSED` (→ OWNER), `TRIP_CHANGE_DECIDED` (→ MEMBER đề xuất).
- Publish event trong service, listener gửi như các type hiện có.

---

## 8. Frontend

- MEMBER ở Result: nút "Đổi/Sửa/Xóa" → tạo đề xuất thay vì sửa thẳng; hiển thị trạng thái "Đang chờ duyệt".
- OWNER: badge "N đề xuất chờ duyệt"; panel duyệt (approve/reject) — tái dùng layout GroupPanel.
- `Result.tsx` hiện hardcode `isOwner={true}` ở `GroupPanel` → cần xác định owner thật từ `TripMember`/`trip.user` để rẽ nhánh UI.

---

## 9. Phân rã issue (đề xuất)

1. `01-entity-and-repo` — TripChangeProposal entity + repo + enums.
2. `02-proposal-api` — controller + service (create/list/withdraw).
3. `03-approve-execute` — OWNER duyệt → thực thi UPDATE/DELETE/SWAP trong tx.
4. `04-notifications` — 2 type + event/listener.
5. `05-authz` — requireTripMember / requireOwner; nới guard owner-only hiện tại.
6. `06-fe-member-propose` — UI đề xuất cho MEMBER.
7. `07-fe-owner-review` — UI duyệt cho OWNER + badge.

---

## 10. Open questions (chốt trước khi code)

1. SWAP credit: OWNER trả lúc duyệt — đồng ý? Xử lý session TTL theo hướng (a) hay (b)?
2. ADD/REORDER có vào v1 không (hiện đề xuất v2)?
3. MEMBER có được tự ý REORDER (ít rủi ro) mà không cần duyệt không?
4. Giới hạn số proposal PENDING mỗi member/trip để tránh spam?
