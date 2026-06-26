-- ============================================================================
-- Reference migration: premium trip entitlement columns
-- Date: 2026-06-25
--
-- Purpose:
--   The project currently uses Hibernate ddl-auto=update. These columns are
--   created automatically on boot, but running this script explicitly is safer
--   before enabling external warehouse syncs such as PostHog MSSQL sync.
--
-- Columns added by the credit-backed premium feature:
--   generated_as_premium      BIT NOT NULL DEFAULT 0
--   activity_swap_free_limit  INT NOT NULL DEFAULT 0
--   activity_swap_free_used   INT NOT NULL DEFAULT 0
--
-- This script is idempotent for normal SQL Server deployments:
--   1. Add missing columns as nullable.
--   2. Backfill existing rows.
--   3. Add default constraints if missing.
--   4. Alter columns to NOT NULL.
-- ============================================================================

SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF COL_LENGTH('dbo.trips', 'generated_as_premium') IS NULL
    ALTER TABLE dbo.trips ADD generated_as_premium BIT NULL;

IF COL_LENGTH('dbo.trips', 'activity_swap_free_limit') IS NULL
    ALTER TABLE dbo.trips ADD activity_swap_free_limit INT NULL;

IF COL_LENGTH('dbo.trips', 'activity_swap_free_used') IS NULL
    ALTER TABLE dbo.trips ADD activity_swap_free_used INT NULL;

UPDATE dbo.trips
SET generated_as_premium = COALESCE(generated_as_premium, 0),
    activity_swap_free_limit = COALESCE(activity_swap_free_limit, 0),
    activity_swap_free_used = COALESCE(activity_swap_free_used, 0)
WHERE generated_as_premium IS NULL
   OR activity_swap_free_limit IS NULL
   OR activity_swap_free_used IS NULL;

IF NOT EXISTS (
    SELECT 1
    FROM sys.default_constraints dc
    JOIN sys.columns c ON c.default_object_id = dc.object_id
    JOIN sys.tables t ON t.object_id = c.object_id
    JOIN sys.schemas s ON s.schema_id = t.schema_id
    WHERE s.name = 'dbo'
      AND t.name = 'trips'
      AND c.name = 'generated_as_premium'
)
    ALTER TABLE dbo.trips
        ADD CONSTRAINT df_trips_generated_as_premium DEFAULT 0 FOR generated_as_premium;

IF NOT EXISTS (
    SELECT 1
    FROM sys.default_constraints dc
    JOIN sys.columns c ON c.default_object_id = dc.object_id
    JOIN sys.tables t ON t.object_id = c.object_id
    JOIN sys.schemas s ON s.schema_id = t.schema_id
    WHERE s.name = 'dbo'
      AND t.name = 'trips'
      AND c.name = 'activity_swap_free_limit'
)
    ALTER TABLE dbo.trips
        ADD CONSTRAINT df_trips_activity_swap_free_limit DEFAULT 0 FOR activity_swap_free_limit;

IF NOT EXISTS (
    SELECT 1
    FROM sys.default_constraints dc
    JOIN sys.columns c ON c.default_object_id = dc.object_id
    JOIN sys.tables t ON t.object_id = c.object_id
    JOIN sys.schemas s ON s.schema_id = t.schema_id
    WHERE s.name = 'dbo'
      AND t.name = 'trips'
      AND c.name = 'activity_swap_free_used'
)
    ALTER TABLE dbo.trips
        ADD CONSTRAINT df_trips_activity_swap_free_used DEFAULT 0 FOR activity_swap_free_used;

ALTER TABLE dbo.trips ALTER COLUMN generated_as_premium BIT NOT NULL;
ALTER TABLE dbo.trips ALTER COLUMN activity_swap_free_limit INT NOT NULL;
ALTER TABLE dbo.trips ALTER COLUMN activity_swap_free_used INT NOT NULL;

COMMIT TRANSACTION;

SELECT c.name AS column_name,
       TYPE_NAME(c.user_type_id) AS type_name,
       c.is_nullable,
       dc.definition AS default_definition
FROM sys.columns c
JOIN sys.tables t ON c.object_id = t.object_id
JOIN sys.schemas s ON t.schema_id = s.schema_id
LEFT JOIN sys.default_constraints dc ON c.default_object_id = dc.object_id
WHERE s.name = 'dbo'
  AND t.name = 'trips'
  AND c.name IN (
      'generated_as_premium',
      'activity_swap_free_limit',
      'activity_swap_free_used'
  )
ORDER BY c.column_id;
