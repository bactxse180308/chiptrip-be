package com.tranbac.chiptripbe.module.moderation.service;

import com.tranbac.chiptripbe.module.moderation.dto.request.CreateReportRequest;
import com.tranbac.chiptripbe.module.moderation.dto.request.ResolveReportRequest;
import com.tranbac.chiptripbe.module.moderation.dto.response.ReportResponse;
import com.tranbac.chiptripbe.module.moderation.enums.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ModerationService {

    ReportResponse createReport(Long reporterUserId, CreateReportRequest request);

    /** status = null → tất cả. */
    Page<ReportResponse> adminList(ReportStatus status, Pageable pageable);

    long adminCountPending();

    ReportResponse adminResolve(Long adminId, Long reportId, ResolveReportRequest.Action action);
}
