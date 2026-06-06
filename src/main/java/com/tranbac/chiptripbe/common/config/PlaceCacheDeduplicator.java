package com.tranbac.chiptripbe.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Dự án dùng ddl-auto=update, không có Flyway/Liquibase, nên những schema fix
 * mà Hibernate không tự làm được (dedup data, filtered unique index) phải chạy
 * tay tại startup.
 *
 * Runner này:
 * 1) Dedup các row place_cache trùng goong_place_id, giữ lại row "tốt nhất"
 *    (serp_enriched > có rating/reviews/photos/... > updated_at mới > id lớn).
 * 2) Tạo filtered unique index ux_place_cache_goong_place_id_not_null
 *    (chỉ ràng buộc với goong_place_id NOT NULL — Hibernate @Index không tạo được).
 *
 * Idempotent: chạy nhiều lần không crash, không xoá nhầm dữ liệu tốt.
 *
 * Preview duplicate trước khi runner xoá (chạy tay nếu muốn audit):
 *   SELECT goong_place_id, COUNT(*) cnt
 *   FROM place_cache
 *   WHERE goong_place_id IS NOT NULL
 *   GROUP BY goong_place_id
 *   HAVING COUNT(*) > 1;
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class PlaceCacheDeduplicator implements CommandLineRunner {

    private static final String UNIQUE_INDEX_NAME = "ux_place_cache_goong_place_id_not_null";

    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        if (!tableExists("place_cache")) {
            log.debug("place_cache table not found, skipping dedup");
            return;
        }
        dedupByGoongPlaceId();
        ensureUniqueIndex();
    }

    private boolean tableExists(String table) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys.tables WHERE name = ?",
                    Integer.class, table);
            return count != null && count > 0;
        } catch (DataAccessException e) {
            return false;
        }
    }

    private void dedupByGoongPlaceId() {
        try {
            int deleted = jdbc.update("""
                    WITH ranked AS (
                        SELECT id, ROW_NUMBER() OVER (
                            PARTITION BY goong_place_id
                            ORDER BY
                                CASE WHEN serp_enriched = 1 THEN 0 ELSE 1 END,
                                CASE WHEN rating IS NOT NULL THEN 0 ELSE 1 END,
                                CASE WHEN reviews_json IS NOT NULL THEN 0 ELSE 1 END,
                                CASE WHEN photos_json IS NOT NULL THEN 0 ELSE 1 END,
                                CASE WHEN opening_hours_json IS NOT NULL THEN 0 ELSE 1 END,
                                CASE WHEN address IS NOT NULL THEN 0 ELSE 1 END,
                                CASE WHEN latitude IS NOT NULL AND longitude IS NOT NULL THEN 0 ELSE 1 END,
                                updated_at DESC,
                                id DESC
                        ) AS rn
                        FROM place_cache
                        WHERE goong_place_id IS NOT NULL
                    )
                    DELETE FROM ranked WHERE rn > 1
                    """);
            if (deleted > 0) {
                log.info("place_cache dedup: removed {} duplicate row(s) sharing goong_place_id", deleted);
            } else {
                log.debug("place_cache dedup: no duplicates found");
            }
        } catch (DataAccessException e) {
            log.warn("place_cache dedup failed: {}", e.getClass().getSimpleName(), e);
        }
    }

    private void ensureUniqueIndex() {
        try {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys.indexes WHERE name = ? AND object_id = OBJECT_ID('dbo.place_cache')",
                    Integer.class, UNIQUE_INDEX_NAME);
            if (exists != null && exists > 0) {
                return;
            }
            jdbc.execute("""
                    CREATE UNIQUE NONCLUSTERED INDEX %s
                    ON dbo.place_cache(goong_place_id)
                    WHERE goong_place_id IS NOT NULL
                    """.formatted(UNIQUE_INDEX_NAME));
            log.info("Created filtered unique index {} on place_cache(goong_place_id)", UNIQUE_INDEX_NAME);
        } catch (DataAccessException e) {
            log.warn("Failed to create unique index {}: {} — duplicate rows may still exist",
                    UNIQUE_INDEX_NAME, e.getClass().getSimpleName(), e);
        }
    }
}