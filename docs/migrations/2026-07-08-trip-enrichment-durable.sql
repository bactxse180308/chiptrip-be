-- ============================================================================
-- Migration: them cot durable enrichment cho trips
-- Ngay: 2026-07-08 (dot 2 — fix P1)
--
-- Ly do:
--   Enrich FULL ngay 2..N chay o executor in-memory (best-effort) → mat khi app restart /
--   queue DiscardPolicy / provider down lau. Persist trang thai enrich len trip de:
--     - enrichment_pending: FE (TripDetailResponse.enriching) poll dung luc; TripEnrichmentScheduler
--       quet cac trip con pending roi enrich lai tu DB → durable.
--     - enrichment_attempts: tran so lan sweeper thu lai (chan loop vo han cho dia diem khong co data).
--
-- Local dev dang dung spring.jpa.hibernate.ddl-auto=update nen DB moi co the tu them cot.
-- Production/staging nen chay migration nay de ro rang va idempotent.
-- ============================================================================

IF COL_LENGTH('dbo.trips', 'enrichment_pending') IS NULL
BEGIN
    ALTER TABLE dbo.trips
        ADD enrichment_pending bit NOT NULL
            CONSTRAINT df_trips_enrichment_pending DEFAULT 0;
END

IF COL_LENGTH('dbo.trips', 'enrichment_attempts') IS NULL
BEGIN
    ALTER TABLE dbo.trips
        ADD enrichment_attempts INT NOT NULL
            CONSTRAINT df_trips_enrichment_attempts DEFAULT 0;
END
