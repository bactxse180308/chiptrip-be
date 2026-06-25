package com.tranbac.chiptripbe.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SQL Server tự sinh CHECK constraint cho cột @Enumerated(STRING) khi Hibernate
 * tạo bảng lần đầu, chứa đúng tập enum value tại thời điểm đó. Với ddl-auto=update
 * Hibernate KHÔNG re-create constraint khi mình thêm giá trị mới vào enum
 * (vd thêm SUPPORT_REPLY), khiến INSERT bị 23000 (CK violation).
 *
 * Runner này drop các CHECK constraint auto-generated trên những cột enum hay
 * mở rộng. Idempotent — không có constraint thì no-op.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class EnumCheckConstraintFixer implements CommandLineRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        dropAutoCheckConstraints("notifications", "type");
        dropAutoCheckConstraints("checklist_items", "category");
    }

    private void dropAutoCheckConstraints(String table, String column) {
        try {
            // CHECK constraint auto-generated bởi SQL Server có dạng CK__<table>__<column>__<hash>,
            // NHƯNG tên cột bị TRUNCATE (vd "category" → "categ" trong CK__checklist__categ__<hash>),
            // nên KHÔNG lọc theo tên cột (LIKE '%category%' sẽ trượt). Match mọi CHECK constraint
            // auto-generated trên bảng — các bảng được liệt kê ở đây chỉ có 1 cột enum nên an toàn.
            // ('_' là wildcard 1 ký tự trong LIKE; [_] = literal underscore)
            List<String> names = jdbc.queryForList(
                    "SELECT cc.name FROM sys.check_constraints cc " +
                            "JOIN sys.tables t ON cc.parent_object_id = t.object_id " +
                            "WHERE t.name = ? AND cc.name LIKE 'CK[_][_]%'",
                    String.class, table);
            for (String name : names) {
                jdbc.execute("ALTER TABLE dbo." + table + " DROP CONSTRAINT [" + name + "]");
                log.info("Dropped auto-generated CHECK constraint {} on {}.{}", name, table, column);
            }
        } catch (DataAccessException e) {
            log.warn("Failed to drop CHECK constraints on {}.{}: {}", table, column, e.getClass().getSimpleName());
        }
    }
}
