package com.tranbac.chiptripbe.module.trip.service.impl;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.service.TripExportService;
import com.tranbac.chiptripbe.module.trip.service.TripService;
import com.tranbac.chiptripbe.module.user.service.EntitlementService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
class TripExportServiceImpl implements TripExportService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String FONT_FAMILY = "DejaVu Sans";

    // Bảng màu thương hiệu ChipTrip (đồng bộ với FE index.css → đã quy đổi sang HEX cho openhtmltopdf).
    private static final String INK = "#2E251F";
    private static final String CREAM = "#FCFAF8";
    private static final String ORANGE = "#FF8214";
    private static final String ORANGE_INK = "#B84A0A";
    private static final String ORANGE_SOFT = "#FFE7CF";
    private static final String YELLOW = "#F6CE55";
    private static final String YELLOW_SOFT = "#FBF0D0";
    private static final String TEAL = "#0D7482";
    private static final String TEAL_INK = "#0E424E";
    private static final String TEAL_SOFT = "#E2F2F4";
    private static final String GOLD = "#D99F20";
    private static final String GOLD_INK = "#855D19";
    private static final String GOLD_SOFT = "#FBEFCD";
    private static final String ROSE = "#E8617A";
    private static final String INDIGO = "#5B6CB8";
    private static final String INDIGO_SOFT = "#E6E9FA";
    private static final String GREEN = "#1F9D55";   // trạng thái "đã chuẩn bị" của checklist

    /** Màu thanh tiêu đề mỗi ngày — luân phiên cho "sặc sỡ", text trắng/đen theo độ tương phản. */
    private static final String[][] DAY_COLORS = {
            {ORANGE, "#ffffff"},
            {TEAL, "#ffffff"},
            {GOLD, "#3B2A05"},
            {ROSE, "#ffffff"},
            {INDIGO, "#ffffff"},
    };

    private final TripService tripService;
    private final EntitlementService entitlementService;

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
        // Gate "createdAsPremium HOẶC premium hiện tại":
        // - createdAsPremium (snapshot) → không cliff: mua 1 credit, generate (paid→0) vẫn export được.
        // - isPremium hiện tại → nạp gói rồi thì export được cả chuyến tạo lúc còn Normal.
        // PDF không tốn credit → mở khoá không trừ gì.
        if (!trip.isCreatedAsPremium() && !entitlementService.isPremium(userId)) {
            throw AppException.premiumRequired();   // 403 PREMIUM_REQUIRED
        }
        String html = buildHtml(trip);

        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFont(() -> new ByteArrayInputStream(fontBytes), FONT_FAMILY);
            builder.useSVGDrawer(new BatikSVGDrawer());   // vẽ mascot Chip + gradient hero
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

    // ────────────────────────────────────────────────────────────────────────
    // HTML builder
    // ────────────────────────────────────────────────────────────────────────

    private String buildHtml(TripDetailResponse trip) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>")
          .append(css())
          .append("</style></head><body>");

        sb.append(hero(trip));
        sb.append(statBar(trip));
        sb.append(days(trip));
        sb.append(checklist(trip));
        sb.append(footer());

        sb.append("</body></html>");
        return sb.toString();
    }

    private String hero(TripDetailResponse trip) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"hero\">");
        sb.append(heroBgSvg());
        sb.append("<table class=\"hero-inner\"><tr>");
        sb.append("<td class=\"hero-mascot\">").append(chipMascotSvg(86)).append("</td>");
        sb.append("<td class=\"hero-text\">");
        sb.append("<div class=\"brand\">ChipTrip · Lịch trình của bạn</div>");
        sb.append("<div class=\"hero-title\">").append(esc(trip.getTitle())).append("</div>");
        sb.append("<div class=\"hero-route\">")
          .append("<span class=\"pin\">").append(esc(trip.getDeparture())).append("</span>")
          .append("<span class=\"arrow\">&#8594;</span>")
          .append("<span class=\"pin pin-dest\">").append(esc(trip.getDestination())).append("</span>")
          .append("</div>");
        sb.append("</td>");
        sb.append("<td class=\"hero-status\">").append(statusBadge(trip.getStatus())).append("</td>");
        sb.append("</tr></table>");
        sb.append("</div>");
        return sb.toString();
    }

    private String statBar(TripDetailResponse trip) {
        LocalDate s = trip.getDateStart();
        LocalDate e = trip.getDateEnd();
        String when = (s != null && e != null) ? fmtDate(s) + " – " + fmtDate(e) : "—";
        String nights = "";
        if (s != null && e != null) {
            long n = ChronoUnit.DAYS.between(s, e);
            int dayCount = trip.getDays() != null ? trip.getDays().size() : (int) (n + 1);
            nights = dayCount + " ngày " + Math.max(n, 0) + " đêm";
        }
        int people = trip.getPeopleCount() != null ? trip.getPeopleCount() : 0;

        Long budget = trip.getBudgetVnd();
        Long total = trip.getTotalCostVnd();
        String pct = "";
        if (budget != null && budget > 0 && total != null) {
            long p = Math.round(total * 100.0 / budget);
            pct = p + "% ngân sách";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"stats\"><tr>");
        sb.append(statCell("teal", "Thời gian", when, nights));
        sb.append(statCell("orange", "Số người", people + " người", "Đoàn của bạn"));
        sb.append(statCell("gold", "Ngân sách", fmtVnd(budget), "Dự kiến tối đa"));
        sb.append(statCell("rose", "Tổng chi phí", fmtVnd(total), pct.isEmpty() ? "Ước tính" : pct));
        sb.append("</tr></table>");
        return sb.toString();
    }

    private String statCell(String variant, String label, String value, String sub) {
        return "<td class=\"stat-td\"><div class=\"stat c-" + variant + "\">"
                + "<div class=\"stat-label\">" + esc(label) + "</div>"
                + "<div class=\"stat-value\">" + esc(value) + "</div>"
                + "<div class=\"stat-sub\">" + esc(sub) + "</div>"
                + "</div></td>";
    }

    private String days(TripDetailResponse trip) {
        List<TripDetailResponse.DayDetail> days = trip.getDays();
        if (days == null || days.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (TripDetailResponse.DayDetail day : days) {
            int idx = day.getDayNumber() != null ? day.getDayNumber() : 1;
            String[] dc = DAY_COLORS[(Math.max(idx, 1) - 1) % DAY_COLORS.length];
            sb.append("<div class=\"day\">");
            // Header bar
            sb.append("<table class=\"day-head\" style=\"background:").append(dc[0]).append(";color:").append(dc[1]).append(";\"><tr>");
            sb.append("<td class=\"day-no\"><span class=\"day-no-num\">").append(idx).append("</span></td>");
            sb.append("<td class=\"day-head-mid\">");
            sb.append("<div class=\"day-title\">Ngày ").append(idx).append("</div>");
            if (day.getDate() != null) {
                sb.append("<div class=\"day-date\">").append(fmtDate(day.getDate())).append("</div>");
            }
            sb.append("</td>");
            sb.append("<td class=\"day-cost\">").append(fmtVnd(day.getDayCostVnd())).append("</td>");
            sb.append("</tr></table>");

            // Activities
            List<TripDetailResponse.ActivityDetail> acts = day.getActivities();
            if (acts != null && !acts.isEmpty()) {
                sb.append("<table class=\"acts\">");
                for (TripDetailResponse.ActivityDetail a : acts) {
                    String[] tm = typeMeta(a.getType());   // [label, soft-bg, ink]
                    String time = a.getStartTime() != null ? a.getStartTime().format(TIME_FMT) : "—";
                    sb.append("<tr class=\"act\">");
                    sb.append("<td class=\"act-time\"><span class=\"time-pill\">").append(time).append("</span></td>");
                    sb.append("<td class=\"act-main\" style=\"border-left-color:").append(tm[2]).append(";\">");
                    sb.append("<div class=\"act-headline\">");
                    sb.append("<span class=\"act-name\">").append(esc(a.getName())).append("</span>");
                    sb.append("<span class=\"type-badge\" style=\"background:").append(tm[1]).append(";color:").append(tm[2]).append(";\">")
                      .append(tm[0]).append("</span>");
                    sb.append("</div>");
                    if (notBlank(a.getDescription())) {
                        sb.append("<div class=\"act-desc\">").append(esc(a.getDescription())).append("</div>");
                    }
                    if (notBlank(a.getAddress())) {
                        sb.append("<div class=\"act-addr\">").append(pinSvg(tm[2])).append(" ").append(esc(a.getAddress())).append("</div>");
                    }
                    sb.append("</td>");
                    sb.append("<td class=\"act-cost\">").append(fmtVnd(a.getCostVnd())).append("</td>");
                    sb.append("</tr>");
                }
                sb.append("</table>");
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    private String checklist(TripDetailResponse trip) {
        List<TripDetailResponse.ChecklistItemDetail> items = trip.getChecklist();
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"section-head\">").append(chipMascotSvg(26))
          .append("<span>Checklist chuẩn bị</span></div>");
        sb.append("<table class=\"checklist\"><tr>");
        sb.append("<td class=\"chk-col\">");
        int half = (items.size() + 1) / 2;
        for (int i = 0; i < items.size(); i++) {
            if (i == half) sb.append("</td><td class=\"chk-col\">");
            TripDetailResponse.ChecklistItemDetail it = items.get(i);
            boolean checked = Boolean.TRUE.equals(it.getIsChecked());
            String[] cm = categoryMeta(it.getCategory());
            sb.append("<div class=\"chk-item\">");
            sb.append(checkboxSvg(checked, cm[1]));
            sb.append("<span class=\"chk-name").append(checked ? " done" : "").append("\">").append(esc(it.getName())).append("</span>");
            if (cm[0] != null && !cm[0].isEmpty()) {
                sb.append("<span class=\"chk-cat\" style=\"background:").append(cm[2]).append(";color:").append(cm[1]).append(";\">")
                  .append(cm[0]).append("</span>");
            }
            sb.append("</div>");
        }
        sb.append("</td></tr></table>");
        return sb.toString();
    }

    private String footer() {
        return "<div class=\"footer\">" + chipMascotSvg(22)
                + "<span>Lên kế hoạch cùng <b>ChipTrip</b> — 30 giây có lịch trình hoàn hảo.</span>"
                + "</div>";
    }

    // ────────────────────────────────────────────────────────────────────────
    // SVG assets (render bằng Batik)
    // ────────────────────────────────────────────────────────────────────────

    /** Mascot Chip — chú gà con vàng, vẽ vector để nét & sặc sỡ ở mọi kích thước. */
    private String chipMascotSvg(int size) {
        return "<svg width=\"" + size + "\" height=\"" + size + "\" viewBox=\"0 0 100 100\" "
                + "xmlns=\"http://www.w3.org/2000/svg\">"
                // feet
                + "<g stroke=\"" + ORANGE_INK + "\" stroke-width=\"3\" stroke-linecap=\"round\" fill=\"none\">"
                + "<path d=\"M41 84 l-6 9 M41 84 l0 10 M41 84 l6 9\"/>"
                + "<path d=\"M59 84 l-6 9 M59 84 l0 10 M59 84 l6 9\"/></g>"
                // tuft
                + "<path d=\"M50 14 q-3 -9 -9 -8 q5 2 5 9 z\" fill=\"" + GOLD + "\"/>"
                + "<path d=\"M50 14 q3 -9 9 -8 q-5 2 -5 9 z\" fill=\"" + GOLD + "\"/>"
                + "<path d=\"M50 12 q0 -10 0 -10 q4 5 1 11 z\" fill=\"" + GOLD + "\"/>"
                // body
                + "<ellipse cx=\"50\" cy=\"55\" rx=\"35\" ry=\"33\" fill=\"" + YELLOW + "\"/>"
                + "<ellipse cx=\"50\" cy=\"63\" rx=\"24\" ry=\"21\" fill=\"#FBE08A\"/>"
                // wing
                + "<path d=\"M21 53 q-7 9 1 19 q7 -4 9 -15 z\" fill=\"" + GOLD + "\"/>"
                // cheeks
                + "<circle cx=\"33\" cy=\"60\" r=\"5\" fill=\"#FF9D8A\"/>"
                + "<circle cx=\"67\" cy=\"60\" r=\"5\" fill=\"#FF9D8A\"/>"
                // eyes
                + "<circle cx=\"41\" cy=\"49\" r=\"5\" fill=\"" + INK + "\"/>"
                + "<circle cx=\"59\" cy=\"49\" r=\"5\" fill=\"" + INK + "\"/>"
                + "<circle cx=\"42.6\" cy=\"47.4\" r=\"1.7\" fill=\"#ffffff\"/>"
                + "<circle cx=\"60.6\" cy=\"47.4\" r=\"1.7\" fill=\"#ffffff\"/>"
                // beak
                + "<path d=\"M50 55 l7 6 l-7 6 l-7 -6 z\" fill=\"" + ORANGE + "\"/>"
                + "</svg>";
    }

    /** Nền hero: gradient cam→vàng bo góc + chấm trang trí + nét đường bay đứt khúc. */
    private String heroBgSvg() {
        return "<svg class=\"hero-bg\" width=\"676\" height=\"150\" viewBox=\"0 0 676 150\" "
                + "preserveAspectRatio=\"none\" xmlns=\"http://www.w3.org/2000/svg\">"
                + "<defs><linearGradient id=\"hg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">"
                + "<stop offset=\"0\" stop-color=\"" + ORANGE + "\"/>"
                + "<stop offset=\"0.55\" stop-color=\"#FB9E2C\"/>"
                + "<stop offset=\"1\" stop-color=\"" + YELLOW + "\"/></linearGradient></defs>"
                + "<rect x=\"0\" y=\"0\" width=\"676\" height=\"150\" rx=\"20\" fill=\"url(#hg)\"/>"
                + "<g fill=\"#ffffff\" opacity=\"0.13\">"
                + "<circle cx=\"610\" cy=\"30\" r=\"46\"/>"
                + "<circle cx=\"660\" cy=\"120\" r=\"30\"/>"
                + "<circle cx=\"560\" cy=\"128\" r=\"14\"/></g>"
                + "<path d=\"M120 120 C 220 60, 340 150, 470 70\" stroke=\"#ffffff\" stroke-width=\"2\" "
                + "stroke-dasharray=\"2 6\" stroke-linecap=\"round\" opacity=\"0.45\" fill=\"none\"/>"
                + "</svg>";
    }

    private String pinSvg(String color) {
        return "<svg width=\"9\" height=\"11\" viewBox=\"0 0 24 30\" xmlns=\"http://www.w3.org/2000/svg\">"
                + "<path d=\"M12 0 C5 0 0 5 0 12 C0 21 12 30 12 30 C12 30 24 21 24 12 C24 5 19 0 12 0 Z\" fill=\""
                + color + "\"/><circle cx=\"12\" cy=\"12\" r=\"4.5\" fill=\"#ffffff\"/></svg>";
    }

    private String checkboxSvg(boolean checked, String color) {
        if (checked) {
            return "<svg class=\"chk-box\" width=\"15\" height=\"15\" viewBox=\"0 0 20 20\" xmlns=\"http://www.w3.org/2000/svg\">"
                    + "<rect x=\"0\" y=\"0\" width=\"20\" height=\"20\" rx=\"5\" fill=\"" + GREEN + "\"/>"
                    + "<path d=\"M5 10.5 l3.2 3.4 l6.4 -7.2\" stroke=\"#ffffff\" stroke-width=\"2.4\" "
                    + "fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>";
        }
        return "<svg class=\"chk-box\" width=\"15\" height=\"15\" viewBox=\"0 0 20 20\" xmlns=\"http://www.w3.org/2000/svg\">"
                + "<rect x=\"1\" y=\"1\" width=\"18\" height=\"18\" rx=\"5\" fill=\"#ffffff\" stroke=\"" + color
                + "\" stroke-width=\"2\"/></svg>";
    }

    private String statusBadge(String status) {
        if (status == null) return "";
        String label;
        String bg;
        switch (status) {
            case "ONGOING" -> { label = "Đang đi"; bg = "#1F9D55"; }
            case "COMPLETED" -> { label = "Đã hoàn thành"; bg = TEAL_INK; }
            default -> { label = "Sắp khởi hành"; bg = ORANGE_INK; }
        }
        return "<span class=\"status\" style=\"background:" + bg + ";\">" + label + "</span>";
    }

    /** [label, soft-bg, ink] cho từng loại hoạt động. */
    private String[] typeMeta(ActivityType type) {
        if (type == null) return new String[]{"Khác", "#EFEAE4", "#5B4F45"};
        return switch (type) {
            case FOOD -> new String[]{"Ăn uống", ORANGE_SOFT, ORANGE_INK};
            case ATTRACTION -> new String[]{"Tham quan", TEAL_SOFT, TEAL_INK};
            case ACCOMMODATION -> new String[]{"Lưu trú", GOLD_SOFT, GOLD_INK};
            case TRANSPORT -> new String[]{"Di chuyển", INDIGO_SOFT, INDIGO};
            case OTHER -> new String[]{"Khác", "#EFEAE4", "#5B4F45"};
        };
    }

    /** [label, ink, soft-bg] cho category checklist (DTO trả String). */
    private String[] categoryMeta(String category) {
        if (category == null) return new String[]{"", "#5B4F45", "#EFEAE4"};
        return switch (category) {
            case "PAPERS" -> new String[]{"Giấy tờ", TEAL_INK, TEAL_SOFT};
            case "CLOTHES" -> new String[]{"Quần áo", ORANGE_INK, ORANGE_SOFT};
            case "HYGIENE" -> new String[]{"Vệ sinh", GOLD_INK, GOLD_SOFT};
            default -> new String[]{"Khác", "#5B4F45", "#EFEAE4"};
        };
    }

    // ────────────────────────────────────────────────────────────────────────
    // CSS
    // ────────────────────────────────────────────────────────────────────────

    private String css() {
        return "@page { size: A4; margin: 1.5cm; }"
                + "body { font-family: \"" + FONT_FAMILY + "\"; font-size: 10.5px; color: " + INK + "; background: " + CREAM + "; line-height: 1.5; }"
                + "table { border-collapse: collapse; }"

                // Hero
                + ".hero { position: relative; height: 150px; margin-bottom: 14px; }"
                + ".hero-bg { position: absolute; top: 0; left: 0; z-index: -1; }"
                + ".hero-inner { width: 100%; height: 150px; }"
                + ".hero-mascot { width: 96px; text-align: center; vertical-align: middle; padding-left: 12px; }"
                + ".hero-text { vertical-align: middle; padding: 0 6px; }"
                + ".hero-status { vertical-align: top; text-align: right; padding: 14px 18px 0 0; width: 110px; }"
                + ".brand { color: #7A2E00; font-size: 10px; font-weight: bold; letter-spacing: 1px; text-transform: uppercase; }"
                + ".hero-title { color: #ffffff; font-size: 22px; font-weight: bold; line-height: 1.15; margin: 3px 0 7px 0; }"
                + ".hero-route { font-size: 12px; }"
                + ".pin { display: inline-block; background: rgba(255,255,255,0.92); color: " + ORANGE_INK + "; font-weight: bold; padding: 2px 9px; border-radius: 9px; }"
                + ".pin-dest { background: " + INK + "; color: #ffffff; }"
                + ".arrow { color: #ffffff; font-weight: bold; padding: 0 7px; font-size: 14px; }"
                + ".status { display: inline-block; color: #ffffff; font-size: 9.5px; font-weight: bold; padding: 4px 10px; border-radius: 20px; }"

                // Stat bar
                + ".stats { width: 100%; table-layout: fixed; margin-bottom: 16px; }"
                + ".stat-td { width: 25%; padding: 0 4px; vertical-align: top; }"
                + ".stat { border-radius: 12px; padding: 8px 11px; }"
                + ".stat-label { font-size: 8.5px; font-weight: bold; letter-spacing: 0.5px; text-transform: uppercase; }"
                + ".stat-value { font-size: 13.5px; font-weight: bold; margin: 2px 0 1px 0; }"
                + ".stat-sub { font-size: 8.5px; }"
                + ".c-teal { background: " + TEAL_SOFT + "; color: " + TEAL_INK + "; border: 1px solid #BFE3E7; }"
                + ".c-orange { background: " + ORANGE_SOFT + "; color: " + ORANGE_INK + "; border: 1px solid #FAD3B4; }"
                + ".c-gold { background: " + GOLD_SOFT + "; color: " + GOLD_INK + "; border: 1px solid #EFDCA6; }"
                + ".c-rose { background: #FCE3E8; color: #B23A52; border: 1px solid #F6C9D2; }"

                // Day
                + ".day { margin-bottom: 13px; }"
                + ".day-head { width: 100%; table-layout: fixed; border-radius: 11px; }"
                + ".day-no { width: 42px; text-align: center; vertical-align: middle; }"
                + ".day-no-num { display: inline-block; font-size: 20px; font-weight: bold; }"
                + ".day-head-mid { vertical-align: middle; padding: 7px 0; }"
                + ".day-title { font-size: 14px; font-weight: bold; }"
                + ".day-date { font-size: 9.5px; }"
                + ".day-cost { vertical-align: middle; text-align: right; padding-right: 14px; font-size: 12px; font-weight: bold; width: 130px; }"

                // Activities
                + ".acts { width: 100%; table-layout: fixed; margin-top: 5px; }"
                + ".act > td { padding: 7px 0; border-bottom: 1px solid #EFE7DD; vertical-align: top; }"
                + ".act-time { width: 52px; text-align: center; }"
                + ".time-pill { display: inline-block; background: " + INK + "; color: #ffffff; font-size: 9px; font-weight: bold; padding: 2px 6px; border-radius: 7px; }"
                + ".act-main { padding-left: 10px !important; padding-right: 8px !important; border-left: 3px solid " + ORANGE + "; }"
                + ".act-headline { margin-bottom: 1px; }"
                + ".act-name { font-size: 11.5px; font-weight: bold; }"
                + ".type-badge { display: inline-block; font-size: 8px; font-weight: bold; padding: 1px 7px; border-radius: 20px; margin-left: 6px; vertical-align: middle; }"
                + ".act-desc { font-size: 9.5px; color: #6A5E52; margin-top: 1px; }"
                + ".act-addr { font-size: 9px; color: #8A7D70; margin-top: 2px; }"
                + ".act-cost { width: 92px; text-align: right; font-size: 10.5px; font-weight: bold; color: " + ORANGE_INK + "; }"

                // Section heading
                + ".section-head { font-size: 15px; font-weight: bold; color: " + TEAL_INK + "; margin: 6px 0 8px 0; vertical-align: middle; }"
                + ".section-head svg { vertical-align: middle; margin-right: 4px; }"
                + ".section-head span { vertical-align: middle; }"

                // Checklist
                + ".checklist { width: 100%; table-layout: fixed; }"
                + ".chk-col { width: 50%; vertical-align: top; padding-right: 12px; }"
                + ".chk-item { padding: 4px 0; }"
                + ".chk-box { vertical-align: middle; margin-right: 6px; }"
                + ".chk-name { font-size: 10.5px; vertical-align: middle; }"
                + ".chk-name.done { color: #9A8E80; text-decoration: line-through; }"
                + ".chk-cat { display: inline-block; font-size: 8px; font-weight: bold; padding: 1px 6px; border-radius: 20px; margin-left: 6px; vertical-align: middle; }"

                // Footer
                + ".footer { margin-top: 16px; padding-top: 9px; border-top: 2px solid " + YELLOW + "; font-size: 9.5px; color: #8A7D70; text-align: center; }"
                + ".footer svg { vertical-align: middle; margin-right: 5px; }"
                + ".footer span { vertical-align: middle; }";
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String fmtVnd(Long cost) {
        long value = cost != null ? cost : 0L;
        return String.format(Locale.US, "%,d", value).replace(',', '.') + "đ";
    }

    private String fmtDate(LocalDate date) {
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
