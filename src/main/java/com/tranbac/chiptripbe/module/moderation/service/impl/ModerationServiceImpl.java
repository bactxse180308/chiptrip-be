package com.tranbac.chiptripbe.module.moderation.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.moderation.dto.request.CreateReportRequest;
import com.tranbac.chiptripbe.module.moderation.dto.request.ResolveReportRequest;
import com.tranbac.chiptripbe.module.moderation.dto.response.ReportResponse;
import com.tranbac.chiptripbe.module.moderation.entity.ContentReport;
import com.tranbac.chiptripbe.module.moderation.enums.ReportStatus;
import com.tranbac.chiptripbe.module.moderation.enums.ReportTargetType;
import com.tranbac.chiptripbe.module.moderation.repository.ContentReportRepository;
import com.tranbac.chiptripbe.module.moderation.service.ModerationService;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripComment;
import com.tranbac.chiptripbe.module.trip.repository.TripCommentRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripSocialService;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ModerationServiceImpl implements ModerationService {

    private final ContentReportRepository reportRepository;
    private final TripCommentRepository tripCommentRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final TripSocialService tripSocialService;

    @Override
    @Transactional
    public ReportResponse createReport(Long reporterUserId, CreateReportRequest request) {
        validateTargetExists(request.getTargetType(), request.getTargetId());

        if (reportRepository.existsByReporterUserIdAndTargetTypeAndTargetIdAndStatus(
                reporterUserId, request.getTargetType(), request.getTargetId(), ReportStatus.PENDING)) {
            throw AppException.conflict("Bạn đã báo cáo nội dung này rồi");
        }

        ContentReport report = reportRepository.save(ContentReport.builder()
                .reporterUserId(reporterUserId)
                .targetType(request.getTargetType())
                .targetId(request.getTargetId())
                .reason(request.getReason())
                .status(ReportStatus.PENDING)
                .build());
        log.info("User id={} reported {} id={}", reporterUserId, request.getTargetType(), request.getTargetId());
        return toResponse(report);
    }

    @Override
    public Page<ReportResponse> adminList(ReportStatus status, Pageable pageable) {
        Page<ContentReport> page = (status == null)
                ? reportRepository.findAllByOrderByCreatedAtDesc(pageable)
                : reportRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        return page.map(this::toResponse);
    }

    @Override
    public long adminCountPending() {
        return reportRepository.countByStatus(ReportStatus.PENDING);
    }

    @Override
    @Transactional
    public ReportResponse adminResolve(Long adminId, Long reportId, ResolveReportRequest.Action action) {
        ContentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy báo cáo"));
        if (report.getStatus() != ReportStatus.PENDING) {
            throw AppException.badRequest("Báo cáo này đã được xử lý");
        }

        if (action == ResolveReportRequest.Action.DELETE_CONTENT) {
            deleteReportedContent(report.getTargetType(), report.getTargetId());
            // Đóng tất cả report PENDING cùng target (nhiều người có thể report 1 nội dung)
            resolveAllPendingForTarget(report.getTargetType(), report.getTargetId(), adminId);
            report = reportRepository.findById(reportId).orElseThrow();
        } else {
            markResolved(report, ReportStatus.DISMISSED, adminId);
        }
        log.info("Admin id={} resolved report id={} action={}", adminId, reportId, action);
        return toResponse(report);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private void validateTargetExists(ReportTargetType type, Long targetId) {
        boolean exists = switch (type) {
            case TRIP_COMMENT -> tripCommentRepository.existsById(targetId);
            case PUBLIC_TRIP -> tripRepository.findById(targetId).map(Trip::isPublic).orElse(false);
        };
        if (!exists) {
            throw AppException.notFound("Nội dung báo cáo không tồn tại hoặc không công khai");
        }
    }

    private void deleteReportedContent(ReportTargetType type, Long targetId) {
        switch (type) {
            case TRIP_COMMENT -> {
                if (tripCommentRepository.existsById(targetId)) {
                    tripSocialService.adminDeleteComment(targetId);
                }
            }
            case PUBLIC_TRIP -> tripSocialService.adminUnpublishTrip(targetId);
        }
    }

    private void resolveAllPendingForTarget(ReportTargetType type, Long targetId, Long adminId) {
        for (ContentReport r : reportRepository.findByTargetTypeAndTargetIdAndStatus(type, targetId, ReportStatus.PENDING)) {
            markResolved(r, ReportStatus.RESOLVED, adminId);
        }
    }

    private void markResolved(ContentReport report, ReportStatus status, Long adminId) {
        report.setStatus(status);
        report.setResolvedByAdminId(adminId);
        report.setResolvedAt(LocalDateTime.now());
        reportRepository.save(report);
    }

    private ReportResponse toResponse(ContentReport r) {
        String reporterName = userRepository.findById(r.getReporterUserId())
                .map(u -> u.getFullName()).orElse(null);
        String preview = null;
        Long tripId = null;
        switch (r.getTargetType()) {
            case TRIP_COMMENT -> {
                TripComment c = tripCommentRepository.findById(r.getTargetId()).orElse(null);
                if (c != null) {
                    preview = c.getContent();
                    tripId = c.getTripId();
                }
            }
            case PUBLIC_TRIP -> {
                tripId = r.getTargetId();
                preview = tripRepository.findById(r.getTargetId()).map(Trip::getTitle).orElse(null);
            }
        }
        return ReportResponse.builder()
                .id(r.getId())
                .reporterUserId(r.getReporterUserId())
                .reporterName(reporterName)
                .targetType(r.getTargetType())
                .targetId(r.getTargetId())
                .targetPreview(preview)
                .tripId(tripId)
                .reason(r.getReason())
                .status(r.getStatus())
                .createdAt(r.getCreatedAt())
                .resolvedAt(r.getResolvedAt())
                .build();
    }
}
