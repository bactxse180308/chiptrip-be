-- ============================================================================
-- Migration tham chiếu: Public Trip + Social (trip_likes, trip_comments)
--                       + User Reviews (place_reviews)
-- Ngày: 2026-06-11
--
-- LƯU Ý: Project đang dùng hibernate.ddl-auto=update nên cột/bảng/index/unique
-- constraint sẽ được Hibernate tự tạo khi boot. Script này:
--   1. Là tài liệu schema chuẩn để đối chiếu.
--   2. Bổ sung FK ON DELETE CASCADE mà Hibernate KHÔNG tạo được
--      (vì trip_id/user_id trong entity là cột Long thuần, không map quan hệ).
--      Code hiện tại đã tự cleanup khi xóa trip (TripServiceImpl/AdminTripServiceImpl),
--      chạy phần FK dưới đây nếu muốn an toàn thêm ở DB level.
-- ============================================================================

-- ── 1. Cột mới trên bảng trips (Hibernate tự thêm — đối chiếu) ──────────────
-- is_public      BIT NOT NULL DEFAULT 0
-- published_at   DATETIME2 NULL
-- likes_count    INT NOT NULL DEFAULT 0   (denormalized, sync từ trip_likes)
-- comments_count INT NOT NULL DEFAULT 0   (denormalized, sync từ trip_comments)

-- ── 2. trip_likes (Hibernate tự tạo bảng + unique + index) ──────────────────
-- CREATE TABLE trip_likes (
--   id         BIGINT IDENTITY PRIMARY KEY,
--   trip_id    BIGINT NOT NULL,
--   user_id    BIGINT NOT NULL,
--   created_at DATETIME2 NOT NULL,
--   CONSTRAINT uk_trip_likes_trip_user UNIQUE (trip_id, user_id)
-- );

-- FK cascade (chạy tay — optional, code đã cleanup khi xóa trip):
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'fk_trip_likes_trip')
  ALTER TABLE trip_likes ADD CONSTRAINT fk_trip_likes_trip
    FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE;
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'fk_trip_likes_user')
  ALTER TABLE trip_likes ADD CONSTRAINT fk_trip_likes_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- ── 3. trip_comments (Hibernate tự tạo bảng + index + FK user) ──────────────
-- parent_id NULL = comment gốc; nested không giới hạn độ sâu.
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'fk_trip_comments_trip')
  ALTER TABLE trip_comments ADD CONSTRAINT fk_trip_comments_trip
    FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE;
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'fk_trip_comments_parent')
  ALTER TABLE trip_comments ADD CONSTRAINT fk_trip_comments_parent
    FOREIGN KEY (parent_id) REFERENCES trip_comments(id) ON DELETE NO ACTION;
    -- NO ACTION: service đệ quy xóa children trước khi xóa parent

-- ── 4. place_reviews (Hibernate tự tạo bảng + unique + FK user) ─────────────
-- rating TINYINT 1-5, mỗi user 1 review / địa điểm (uk_place_reviews_place_user)
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'fk_place_reviews_place')
  ALTER TABLE place_reviews ADD CONSTRAINT fk_place_reviews_place
    FOREIGN KEY (place_cache_id) REFERENCES place_cache(id) ON DELETE CASCADE;

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_place_reviews_rating')
  ALTER TABLE place_reviews ADD CONSTRAINT ck_place_reviews_rating
    CHECK (rating BETWEEN 1 AND 5);

-- ── 5. Resync denormalized counters (chạy nếu nghi ngờ lệch) ────────────────
-- UPDATE t SET likes_count = (SELECT COUNT(*) FROM trip_likes l WHERE l.trip_id = t.id),
--              comments_count = (SELECT COUNT(*) FROM trip_comments c WHERE c.trip_id = t.id)
-- FROM trips t;
