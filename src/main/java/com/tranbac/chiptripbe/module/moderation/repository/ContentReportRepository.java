package com.tranbac.chiptripbe.module.moderation.repository;

import com.tranbac.chiptripbe.module.moderation.entity.ContentReport;
import com.tranbac.chiptripbe.module.moderation.enums.ReportStatus;
import com.tranbac.chiptripbe.module.moderation.enums.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {

    Page<ContentReport> findByStatusOrderByCreatedAtDesc(ReportStatus status, Pageable pageable);

    Page<ContentReport> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByReporterUserIdAndTargetTypeAndTargetIdAndStatus(
            Long reporterUserId, ReportTargetType targetType, Long targetId, ReportStatus status);

    /** Các report PENDING cùng target — dùng để resolve hàng loạt khi admin xử lý 1 nội dung. */
    List<ContentReport> findByTargetTypeAndTargetIdAndStatus(
            ReportTargetType targetType, Long targetId, ReportStatus status);

    long countByStatus(ReportStatus status);
}
