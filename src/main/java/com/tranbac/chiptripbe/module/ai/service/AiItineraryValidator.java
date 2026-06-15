package com.tranbac.chiptripbe.module.ai.service;

import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.dto.AiItineraryResult;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Strict validation cho AI output trước khi persist. Fail-fast nếu invalid để
 * không lưu trip lỗi vào DB.
 */
@Component
public class AiItineraryValidator {

    /** Cho phép tổng cost vượt budget tối đa 10%. */
    private static final double BUDGET_TOLERANCE = 1.10;

    /** Sàn tolerance tuyệt đối: budget nhỏ vẫn được hở 1 triệu VNĐ thay vì bị 10% siết quá chặt. */
    private static final long BUDGET_TOLERANCE_FLOOR_VND = 1_000_000L;

    /**
     * Số hoạt động tối thiểu mỗi ngày — sàn an toàn bắt ngày "rỗng nội dung".
     * Prompt yêu cầu 5-7; giữ sàn thấp (3) để không tốn lượt retry (maxRetries=1) cho
     * ngày đi/về thưa hợp lệ, chỉ chặn trường hợp lịch trình quá sơ sài.
     */
    private static final int MIN_ACTIVITIES_PER_DAY = 3;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "FOOD", "ATTRACTION", "TRANSPORT", "ACCOMMODATION", "OTHER");

    /** Các pattern searchQuery generic — không đủ cụ thể để geocode chính xác. */
    private static final List<String> GENERIC_SEARCH_QUERIES = List.of(
            "nha hang dia phuong",
            "quan an gan day",
            "quan an dia phuong",
            "khu vui choi",
            "dia diem tham quan",
            "cafe dep",
            "quan cafe dep",
            "diem an uong",
            "diem tham quan",
            "diem du lich",
            // Accommodation generic — AI phải sinh tên khách sạn/homestay cụ thể
            "khach san trung tam",
            "khach san gan trung tam",
            "homestay gan cho",
            "nha nghi gia re"
    );

    public void validate(AiItineraryResult result, GenerateTripRequest request) {
        if (result == null) throw badAi("AI không trả về lịch trình");

        validateTitle(result, request);
        validateDays(result, request);
        long totalCost = validateActivitiesAndComputeCost(result);
        validateBudget(totalCost, request);
        validateChecklist(result);
    }

    /** #3: title phải có và nhắc đến điểm đến (đóng bug "title không khớp input"). */
    private void validateTitle(AiItineraryResult result, GenerateTripRequest request) {
        String title = result.getTitle();
        if (title == null || title.isBlank()) {
            throw badAi("thiếu title");
        }
        String destination = request.getDestination();
        if (destination == null || destination.isBlank()) return; // không có gì để đối chiếu
        // Bỏ phần tỉnh sau dấu phẩy: "Đà Lạt, Lâm Đồng" -> "Đà Lạt".
        String core = destination.split(",")[0];
        if (!normalize(title).contains(normalize(core))) {
            throw badAi(String.format("title '%s' không nhắc đến điểm đến '%s'", title, core.trim()));
        }
    }

    /** #5: checklist là bắt buộc theo prompt — chặn việc AI bỏ qua. */
    private void validateChecklist(AiItineraryResult result) {
        List<AiItineraryResult.AiChecklistItem> checklist = result.getChecklist();
        if (checklist == null || checklist.isEmpty()) {
            throw badAi("thiếu checklist chuẩn bị đồ");
        }
        for (AiItineraryResult.AiChecklistItem item : checklist) {
            if (item.getName() == null || item.getName().isBlank()) {
                throw badAi("checklist có mục thiếu name");
            }
        }
    }

    private void validateDays(AiItineraryResult result, GenerateTripRequest request) {
        List<AiItineraryResult.AiDay> days = result.getDays();
        if (days == null || days.isEmpty()) throw badAi("AI trả về lịch trình rỗng");

        long expectedDays = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        if (days.size() != expectedDays) {
            throw badAi(String.format("AI trả về %d ngày, yêu cầu %d ngày", days.size(), expectedDays));
        }

        Set<Integer> dayNumbersSeen = new HashSet<>();
        for (AiItineraryResult.AiDay day : days) {
            if (day.getDayNumber() == null) throw badAi("Ngày trong lịch trình thiếu dayNumber");
            if (!dayNumbersSeen.add(day.getDayNumber())) {
                throw badAi("Trùng dayNumber=" + day.getDayNumber());
            }

            if (day.getDate() == null || day.getDate().isBlank()) {
                throw badAi("Ngày " + day.getDayNumber() + " thiếu date");
            }
            LocalDate parsedDate;
            try {
                parsedDate = LocalDate.parse(day.getDate());
            } catch (Exception e) {
                throw badAi("Ngày " + day.getDayNumber() + " có date không hợp lệ: " + day.getDate());
            }
            if (parsedDate.isBefore(request.getStartDate()) || parsedDate.isAfter(request.getEndDate())) {
                throw badAi(String.format("Ngày %d (%s) nằm ngoài khoảng %s..%s",
                        day.getDayNumber(), parsedDate, request.getStartDate(), request.getEndDate()));
            }
        }
    }

    private long validateActivitiesAndComputeCost(AiItineraryResult result) {
        long totalCost = 0;
        for (AiItineraryResult.AiDay day : result.getDays()) {
            List<AiItineraryResult.AiActivity> activities = day.getActivities();
            if (activities == null || activities.isEmpty()) {
                throw badAi("Ngày " + day.getDayNumber() + " không có hoạt động");
            }
            if (activities.size() < MIN_ACTIVITIES_PER_DAY) {
                throw badAi(String.format("Ngày %d chỉ có %d hoạt động, cần tối thiểu %d",
                        day.getDayNumber(), activities.size(), MIN_ACTIVITIES_PER_DAY));
            }

            for (AiItineraryResult.AiActivity activity : activities) {
                if (activity.getName() == null || activity.getName().isBlank()) {
                    throw badAi("Ngày " + day.getDayNumber() + " có activity thiếu name");
                }

                if (activity.getTime() == null || activity.getTime().isBlank()) {
                    throw badAi("Activity '" + activity.getName() + "' thiếu time");
                }
                try {
                    LocalTime.parse(activity.getTime(), TIME_FORMAT);
                } catch (Exception e) {
                    throw badAi("Activity '" + activity.getName() + "' có time không hợp lệ: " + activity.getTime());
                }

                String normalizedType = activity.getType() == null
                        ? null
                        : activity.getType().trim().toUpperCase();
                if (normalizedType == null || !ALLOWED_TYPES.contains(normalizedType)) {
                    throw badAi("Activity '" + activity.getName() + "' có type không hợp lệ: " + activity.getType());
                }
                activity.setType(normalizedType);

                if (activity.getCostVnd() == null) {
                    activity.setCostVnd(0L);
                } else if (activity.getCostVnd() < 0) {
                    activity.setCostVnd(0L);
                }
                totalCost += activity.getCostVnd();

                ActivityType type = ActivityType.valueOf(normalizedType);
                if (needsSearchQuery(type)) {
                    String q = activity.getSearchQuery();
                    if (q == null || q.isBlank()) {
                        throw badAi(String.format(
                                "Activity '%s' (type=%s) thiếu searchQuery", activity.getName(), type));
                    }
                    if (isGenericQuery(q)) {
                        throw badAi(String.format(
                                "Activity '%s' (type=%s) có searchQuery generic: '%s'",
                                activity.getName(), type, q));
                    }
                }
            }
        }
        return totalCost;
    }

    private void validateBudget(long totalCost, GenerateTripRequest request) {
        if (request.getBudgetVnd() == null || request.getBudgetVnd() <= 0) return;
        long budget = request.getBudgetVnd();
        long percentTolerance = (long) (budget * (BUDGET_TOLERANCE - 1.0));
        long tolerance = Math.max(percentTolerance, BUDGET_TOLERANCE_FLOOR_VND);
        long maxAllowed = budget + tolerance;
        if (totalCost > maxAllowed) {
            throw badAi(String.format(
                    "Tổng chi phí AI sinh (%,d VNĐ) vượt ngân sách %,d VNĐ quá %,d VNĐ cho phép",
                    totalCost, budget, tolerance));
        }
    }

    /** FOOD, ATTRACTION, ACCOMMODATION, TRANSPORT đều cần searchQuery để geocode. OTHER bỏ qua. */
    private boolean needsSearchQuery(ActivityType type) {
        return type == ActivityType.FOOD
                || type == ActivityType.ATTRACTION
                || type == ActivityType.ACCOMMODATION
                || type == ActivityType.TRANSPORT;
    }

    private boolean isGenericQuery(String q) {
        String normalized = normalize(q);
        for (String generic : GENERIC_SEARCH_QUERIES) {
            if (normalized.equals(generic)) return true;
            // Bắt "Khách sạn trung tâm Đà Lạt" → startsWith "khach san trung tam "
            if (normalized.startsWith(generic + " ")) return true;
        }
        return false;
    }

    private String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[àáạảãâầấậẩẫăằắặẳẵ]", "a")
                .replaceAll("[èéẹẻẽêềếệểễ]", "e")
                .replaceAll("[ìíịỉĩ]", "i")
                .replaceAll("[òóọỏõôồốộổỗơờớợởỡ]", "o")
                .replaceAll("[ùúụủũưừứựửữ]", "u")
                .replaceAll("[ỳýỵỷỹ]", "y")
                .replaceAll("[đ]", "d")
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private AppException badAi(String message) {
        return AppException.badRequest("AI sinh lịch trình không hợp lệ: " + message);
    }
}
