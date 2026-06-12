package com.tranbac.chiptripbe.module.moderation.dto.response;

import com.tranbac.chiptripbe.module.moderation.enums.ReportStatus;
import com.tranbac.chiptripbe.module.moderation.enums.ReportTargetType;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
@Builder
public class ReportResponse {
    private Long id;
    private Long reporterUserId;
    private String reporterName;
    private ReportTargetType targetType;
    private Long targetId;
    /** Nội dung comment / tiêu đề trip bị báo cáo — để admin xem nhanh không cần điều hướng. Null nếu đã bị xóa. */
    private String targetPreview;
    /** tripId chứa nội dung (để admin mở xem). Với PUBLIC_TRIP thì = targetId. */
    private Long tripId;
    private String reason;
    private ReportStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
