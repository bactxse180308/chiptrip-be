package com.tranbac.chiptripbe.module.trip.service.impl;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.service.TripExportService;
import com.tranbac.chiptripbe.module.trip.service.TripService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
class TripExportServiceImpl implements TripExportService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String FONT_FAMILY = "DejaVu Sans";

    private final TripService tripService;

    private byte[] fontBytes;

    @PostConstruct
    void loadFont() {
        try {
            fontBytes = new ClassPathResource("fonts/DejaVuSans.ttf").getContentAsByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Không tải được font PDF (fonts/DejaVuSans.ttf)", e);
        }
    }

    @Override
    public byte[] exportPdf(Long userId, Long tripId) {
        // getTripDetail đã kiểm tra quyền sở hữu (forbidden nếu không phải owner)
        TripDetailResponse trip = tripService.getTripDetail(userId, tripId);
        String html = buildHtml(trip);

        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFont(() -> new ByteArrayInputStream(fontBytes), FONT_FAMILY);
            builder.withHtmlContent(html, "/");
            builder.toStream(os);
            builder.run();
            log.info("Exported PDF for trip id={} by userId={} ({} bytes)", tripId, userId, os.size());
            return os.toByteArray();
        } catch (IOException e) {
            log.error("PDF render failed for trip id={}", tripId, e);
            throw AppException.internal("Không thể tạo PDF lịch trình");
        }
    }

    private String buildHtml(TripDetailResponse trip) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>")
          .append("@page { size: A4; margin: 1.5cm; }")
          .append("body { font-family: \"").append(FONT_FAMILY).append("\"; font-size: 11px; color: #222; }")
          .append("h1 { font-size: 18px; margin: 0 0 4px 0; color: #0d6efd; }")
          .append("h2 { font-size: 14px; margin: 16px 0 6px 0; border-bottom: 1px solid #ddd; padding-bottom: 3px; }")
          .append(".meta { color: #555; margin-bottom: 2px; }")
          .append(".total { font-size: 13px; font-weight: bold; margin-top: 6px; }")
          .append("table { width: 100%; border-collapse: collapse; margin-top: 4px; }")
          .append("th, td { border: 1px solid #ccc; padding: 4px 6px; text-align: left; vertical-align: top; }")
          .append("th { background: #f1f3f5; }")
          .append(".daycost { text-align: right; font-weight: bold; margin-top: 4px; }")
          .append("ul { margin: 4px 0; padding-left: 18px; }")
          .append("</style></head><body>");

        sb.append("<h1>").append(esc(trip.getTitle())).append("</h1>");
        sb.append("<div class=\"meta\">")
          .append(esc(trip.getDeparture())).append(" → ").append(esc(trip.getDestination()))
          .append("</div>");
        sb.append("<div class=\"meta\">")
          .append(fmtDate(trip.getDateStart())).append(" – ").append(fmtDate(trip.getDateEnd()))
          .append(" · ").append(trip.getPeopleCount() != null ? trip.getPeopleCount() : 0).append(" người")
          .append("</div>");
        sb.append("<div class=\"meta\">Ngân sách: ").append(fmtVnd(trip.getBudgetVnd())).append("</div>");
        sb.append("<div class=\"total\">Tổng chi phí dự kiến: ").append(fmtVnd(trip.getTotalCostVnd())).append("</div>");

        List<TripDetailResponse.DayDetail> days = trip.getDays();
        if (days != null) {
            for (TripDetailResponse.DayDetail day : days) {
                sb.append("<h2>Ngày ").append(day.getDayNumber());
                if (day.getDate() != null) {
                    sb.append(" — ").append(fmtDate(day.getDate()));
                }
                sb.append("</h2>");
                sb.append("<table><thead><tr>")
                  .append("<th>Giờ</th><th>Hoạt động</th><th>Mô tả</th><th>Loại</th><th>Chi phí</th><th>Địa chỉ</th>")
                  .append("</tr></thead><tbody>");
                if (day.getActivities() != null) {
                    for (TripDetailResponse.ActivityDetail act : day.getActivities()) {
                        sb.append("<tr>")
                          .append("<td>").append(act.getStartTime() != null ? act.getStartTime().format(TIME_FMT) : "").append("</td>")
                          .append("<td>").append(esc(act.getName())).append("</td>")
                          .append("<td>").append(esc(act.getDescription())).append("</td>")
                          .append("<td>").append(act.getType() != null ? act.getType().name() : "").append("</td>")
                          .append("<td>").append(fmtVnd(act.getCostVnd())).append("</td>")
                          .append("<td>").append(esc(act.getAddress())).append("</td>")
                          .append("</tr>");
                    }
                }
                sb.append("</tbody></table>");
                sb.append("<div class=\"daycost\">Chi phí ngày: ").append(fmtVnd(day.getDayCostVnd())).append("</div>");
            }
        }

        List<TripDetailResponse.ChecklistItemDetail> checklist = trip.getChecklist();
        if (checklist != null && !checklist.isEmpty()) {
            sb.append("<h2>Checklist chuẩn bị</h2><ul>");
            for (TripDetailResponse.ChecklistItemDetail item : checklist) {
                String mark = Boolean.TRUE.equals(item.getIsChecked()) ? "[x] " : "[ ] ";
                sb.append("<li>").append(mark).append(esc(item.getName()));
                if (item.getCategory() != null) {
                    sb.append(" (").append(esc(item.getCategory())).append(")");
                }
                sb.append("</li>");
            }
            sb.append("</ul>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private String fmtVnd(Long cost) {
        long value = cost != null ? cost : 0L;
        return String.format(Locale.US, "%,d", value).replace(',', '.') + " VNĐ";
    }

    private String fmtDate(java.time.LocalDate date) {
        return date != null ? date.format(DATE_FMT) : "";
    }

    private String esc(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
