-- ============================================================================
-- Migration: them negative-cache columns cho place_cache
-- Ngay: 2026-07-08
--
-- Ly do:
--   Backend chi nen bo qua lookup lai khi Goong + SerpApi xac nhan "khong tim thay".
--   Neu provider tam loi/429/timeout/5xx thi khong set cac cot nay.
--
-- Local dev dang dung spring.jpa.hibernate.ddl-auto=update nen DB moi co the tu them cot.
-- Production/staging nen chay migration nay de ro rang va idempotent.
-- ============================================================================

IF COL_LENGTH('dbo.place_cache', 'not_found') IS NULL
BEGIN
    ALTER TABLE dbo.place_cache
        ADD not_found bit NOT NULL
            CONSTRAINT df_place_cache_not_found DEFAULT 0;
END

IF COL_LENGTH('dbo.place_cache', 'not_found_at') IS NULL
BEGIN
    ALTER TABLE dbo.place_cache
        ADD not_found_at DATETIME2 NULL;
END

IF COL_LENGTH('dbo.place_cache', 'not_found_reason') IS NULL
BEGIN
    ALTER TABLE dbo.place_cache
        ADD not_found_reason VARCHAR(255) NULL;
END
