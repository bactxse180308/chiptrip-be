-- Photo refresh state for place_cache (SQL Server).
-- Idempotent: safe to run again during deployment.

IF COL_LENGTH('dbo.place_cache', 'photos_synced_at') IS NULL
BEGIN
    ALTER TABLE dbo.place_cache
        ADD photos_synced_at DATETIME2 NULL;
END

IF COL_LENGTH('dbo.place_cache', 'photos_status') IS NULL
BEGIN
    ALTER TABLE dbo.place_cache
        ADD photos_status VARCHAR(20) NOT NULL
            CONSTRAINT df_place_cache_photos_status DEFAULT 'PENDING';
END

IF COL_LENGTH('dbo.place_cache', 'photo_failure_count') IS NULL
BEGIN
    ALTER TABLE dbo.place_cache
        ADD photo_failure_count INT NOT NULL
            CONSTRAINT df_place_cache_photo_failure_count DEFAULT 0;
END

-- Backfill rows da co anh de tranh coi anh hien tai la PENDING.
UPDATE dbo.place_cache
SET photos_status = 'READY',
    photos_synced_at = COALESCE(photos_synced_at, serp_synced_at, updated_at)
WHERE photos_json IS NOT NULL
  AND LEN(LTRIM(RTRIM(photos_json))) > 0
  AND photos_status = 'PENDING';
