-- ============================================================================
-- Migration: nới cột place_cache.goong_place_id từ VARCHAR(100) → VARCHAR(512)
-- Ngày: 2026-06-30
--
-- LÝ DO: Goong forward-geocode V2 trả place_id dài tới ~270 ký tự (vd "Cầu Trần Phú
-- Nha Trang"). Cột cũ là VARCHAR(100) nên insert ném lỗi:
--   "String or binary data would be truncated in table 'place_cache', column 'goong_place_id'"
-- → activity mất place (resolvePlace skip). Entity đã khai length=512 nhưng
-- hibernate.ddl-auto=update KHÔNG nới cột đã tồn tại → BẮT BUỘC chạy ALTER này tay
-- trên mọi DB đã tạo (production VPS + local cũ). DB tạo mới sẽ tự đúng 512.
--
-- QUAN TRỌNG — drop/recreate index trước khi ALTER:
--   SQL Server không cho ALTER COLUMN khi cột là key của bất kỳ index nào (lỗi 5074).
--   goong_place_id có 2 index:
--     1. ix_place_cache_goong_place_id — non-unique, do Hibernate tạo từ @Index.
--     2. ux_place_cache_goong_place_id_not_null — filtered unique (WHERE NOT NULL),
--        do PlaceCacheDeduplicator.ensureUniqueIndex() tạo mỗi lần app boot.
--   → Phải DROP cả hai trước ALTER, rồi tạo lại ix sau ALTER.
--     ux sẽ tự được PlaceCacheDeduplicator tạo lại khi app khởi động kế tiếp.
--
-- Idempotent: toàn bộ block chỉ chạy khi max_length < 512 (tức chưa nới).
-- An toàn: sau ALTER, ix được tạo lại ngay; ux tạo lại khi boot → không mất dedup.
-- ============================================================================

IF EXISTS (
    SELECT 1
    FROM sys.columns c
    JOIN sys.tables t ON c.object_id = t.object_id
    WHERE t.name = 'place_cache'
      AND c.name = 'goong_place_id'
      AND c.max_length < 512   -- varchar: max_length = số byte = số ký tự
)
BEGIN
    -- Bước 1: Drop non-unique index (Hibernate-managed)
    IF EXISTS (
        SELECT 1 FROM sys.indexes
        WHERE name = 'ix_place_cache_goong_place_id'
          AND object_id = OBJECT_ID('dbo.place_cache')
    )
        DROP INDEX ix_place_cache_goong_place_id ON dbo.place_cache;

    -- Bước 2: Drop filtered unique index (PlaceCacheDeduplicator-managed)
    IF EXISTS (
        SELECT 1 FROM sys.indexes
        WHERE name = 'ux_place_cache_goong_place_id_not_null'
          AND object_id = OBJECT_ID('dbo.place_cache')
    )
        DROP INDEX ux_place_cache_goong_place_id_not_null ON dbo.place_cache;

    -- Bước 3: Nới cột (bây giờ an toàn — không còn index phụ thuộc)
    ALTER TABLE place_cache ALTER COLUMN goong_place_id VARCHAR(512) NULL;

    -- Bước 4: Tạo lại non-unique index
    CREATE NONCLUSTERED INDEX ix_place_cache_goong_place_id
        ON dbo.place_cache(goong_place_id);

    -- LƯU Ý: ux_place_cache_goong_place_id_not_null (filtered unique) sẽ được
    -- PlaceCacheDeduplicator.ensureUniqueIndex() tạo lại tự động khi app khởi động.
    -- Không cần recreate ở đây.
END
