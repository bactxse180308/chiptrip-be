-- Durable retry schedule for trip enrichment (SQL Server).
-- Idempotent: safe to run again during deployment.

-- Required by SQL Server when creating a filtered index.
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;
GO

IF COL_LENGTH('dbo.trips', 'enrichment_next_attempt_at') IS NULL
BEGIN
    ALTER TABLE dbo.trips
        ADD enrichment_next_attempt_at DATETIME2 NULL;
END

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = 'ix_trips_enrichment_retry_due'
      AND object_id = OBJECT_ID('dbo.trips')
)
BEGIN
    CREATE INDEX ix_trips_enrichment_retry_due
        ON dbo.trips (enrichment_next_attempt_at, created_at)
        INCLUDE (enrichment_attempts, user_id)
        WHERE enrichment_pending = 1;
END
