package com.tranbac.chiptripbe.module.ai.service;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.dto.AiItineraryResult;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verify {@link AiItineraryValidator} bắt được các loại lỗi mà Task 2 yêu cầu retry:
 * - Số ngày lệch
 * - searchQuery generic
 * - Tổng cost vượt ngân sách
 * Và pass với input hợp lệ.
 */
class AiItineraryValidatorTest {

    private final AiItineraryValidator validator = new AiItineraryValidator();

    @Test
    void validate_validInput_noException() {
        GenerateTripRequest req = request(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2), 5_000_000L);
        AiItineraryResult result = itinerary(
                day(1, "2026-07-01", List.of(
                        activity("08:00", "Phở Hà Nội", "FOOD", "Phở Bát Đàn Hà Nội", 100_000L),
                        activity("10:00", "Hồ Gươm", "ATTRACTION", "Hồ Hoàn Kiếm Hà Nội", 0L),
                        activity("12:00", "Bún chả", "FOOD", "Bún chả Đắc Kim Hà Nội", 80_000L))),
                day(2, "2026-07-02", List.of(
                        activity("08:00", "Văn Miếu", "ATTRACTION", "Văn Miếu Quốc Tử Giám Hà Nội", 30_000L),
                        activity("11:00", "Phở cuốn", "FOOD", "Phở cuốn Ngũ Xã Hà Nội", 60_000L),
                        activity("15:00", "Hoàng thành", "ATTRACTION", "Hoàng thành Thăng Long Hà Nội", 30_000L)))
        );

        assertDoesNotThrow(() -> validator.validate(result, req));
    }

    @Test
    void validate_dayCountMismatch_throwsAppException() {
        // Yêu cầu 3 ngày nhưng AI trả 2 ngày
        GenerateTripRequest req = request(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), 5_000_000L);
        AiItineraryResult result = itinerary(
                day(1, "2026-07-01", List.of(activity("08:00", "A", "FOOD", "Phở Bát Đàn Hà Nội", 0L))),
                day(2, "2026-07-02", List.of(activity("09:00", "B", "FOOD", "Bún chả Đắc Kim Hà Nội", 0L)))
        );

        AppException ex = assertThrows(AppException.class, () -> validator.validate(result, req));
        assertTrue(ex.getMessage().contains("2 ngày"), "Phải nhắc số ngày AI trả về: " + ex.getMessage());
    }

    @Test
    void validate_genericSearchQuery_throwsAppException() {
        GenerateTripRequest req = request(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), 5_000_000L);
        AiItineraryResult result = itinerary(
                day(1, "2026-07-01", List.of(
                        activity("08:00", "Ăn sáng", "FOOD", "Phở Bát Đàn Hà Nội", 50_000L),
                        activity("10:00", "Tham quan", "ATTRACTION", "Hồ Hoàn Kiếm Hà Nội", 0L),
                        activity("14:00", "Khách sạn", "ACCOMMODATION", "Khách sạn trung tâm", 1_000_000L)))
        );

        AppException ex = assertThrows(AppException.class, () -> validator.validate(result, req));
        assertTrue(ex.getMessage().toLowerCase().contains("generic"),
                "Phải nhắc lỗi generic searchQuery: " + ex.getMessage());
    }

    @Test
    void validate_overBudget_throwsAppException() {
        // Budget 1M, tolerance là max(10% × 1M, 1M floor) = 1M → maxAllowed = 2M
        // Tổng cost 3M → vượt
        GenerateTripRequest req = request(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), 1_000_000L);
        AiItineraryResult result = itinerary(
                day(1, "2026-07-01", List.of(
                        activity("08:00", "A", "FOOD", "Phở Bát Đàn Hà Nội", 1_500_000L),
                        activity("12:00", "B", "FOOD", "Bún chả Đắc Kim Hà Nội", 1_500_000L),
                        activity("18:00", "C", "FOOD", "Chả cá Lã Vọng Hà Nội", 0L)
                ))
        );

        AppException ex = assertThrows(AppException.class, () -> validator.validate(result, req));
        assertTrue(ex.getMessage().contains("ngân sách"),
                "Phải nhắc lỗi vượt ngân sách: " + ex.getMessage());
    }

    @Test
    void validate_dateOutOfRange_throwsAppException() {
        GenerateTripRequest req = request(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), 5_000_000L);
        // Date nằm ngoài range
        AiItineraryResult result = itinerary(
                day(1, "2026-08-01", List.of(activity("08:00", "A", "FOOD", "Phở Bát Đàn Hà Nội", 0L)))
        );

        AppException ex = assertThrows(AppException.class, () -> validator.validate(result, req));
        assertTrue(ex.getMessage().contains("ngoài khoảng"),
                "Phải nhắc date ngoài khoảng: " + ex.getMessage());
    }

    @Test
    void validate_missingChecklist_throwsAppException() {
        GenerateTripRequest req = request(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), 5_000_000L);
        AiItineraryResult result = itinerary(
                day(1, "2026-07-01", List.of(
                        activity("08:00", "A", "FOOD", "Phở Bát Đàn Hà Nội", 50_000L),
                        activity("10:00", "B", "ATTRACTION", "Hồ Hoàn Kiếm Hà Nội", 0L),
                        activity("12:00", "C", "FOOD", "Bún chả Đắc Kim Hà Nội", 80_000L)))
        );
        result.setChecklist(null);

        AppException ex = assertThrows(AppException.class, () -> validator.validate(result, req));
        assertTrue(ex.getMessage().contains("checklist"), "Phải nhắc thiếu checklist: " + ex.getMessage());
    }

    @Test
    void validate_tooFewActivities_throwsAppException() {
        GenerateTripRequest req = request(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), 5_000_000L);
        AiItineraryResult result = itinerary(
                day(1, "2026-07-01", List.of(
                        activity("08:00", "A", "FOOD", "Phở Bát Đàn Hà Nội", 50_000L),
                        activity("10:00", "B", "ATTRACTION", "Hồ Hoàn Kiếm Hà Nội", 0L)))
        );

        AppException ex = assertThrows(AppException.class, () -> validator.validate(result, req));
        assertTrue(ex.getMessage().contains("tối thiểu"), "Phải nhắc thiếu hoạt động: " + ex.getMessage());
    }

    @Test
    void validate_titleMissingDestination_throwsAppException() {
        GenerateTripRequest req = request(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), 5_000_000L);
        when(req.getDestination()).thenReturn("Đà Lạt");
        AiItineraryResult result = itinerary(
                day(1, "2026-07-01", List.of(
                        activity("08:00", "A", "FOOD", "Bánh căn Nhà Chung Đà Lạt", 50_000L),
                        activity("10:00", "B", "ATTRACTION", "Hồ Xuân Hương Đà Lạt", 0L),
                        activity("12:00", "C", "FOOD", "Nem nướng Đà Lạt", 80_000L)))
        );
        // title mặc định "test" -> không chứa "Đà Lạt"

        AppException ex = assertThrows(AppException.class, () -> validator.validate(result, req));
        assertTrue(ex.getMessage().toLowerCase().contains("điểm đến"),
                "Phải nhắc title thiếu điểm đến: " + ex.getMessage());
    }

    @Test
    void validate_titleWithDestination_noException() {
        GenerateTripRequest req = request(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), 5_000_000L);
        when(req.getDestination()).thenReturn("Đà Lạt");
        AiItineraryResult result = itinerary(
                day(1, "2026-07-01", List.of(
                        activity("08:00", "A", "FOOD", "Bánh căn Nhà Chung Đà Lạt", 50_000L),
                        activity("10:00", "B", "ATTRACTION", "Hồ Xuân Hương Đà Lạt", 0L),
                        activity("12:00", "C", "FOOD", "Nem nướng Đà Lạt", 80_000L)))
        );
        result.setTitle("Hành trình Đà Lạt 1 ngày");

        assertDoesNotThrow(() -> validator.validate(result, req));
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private GenerateTripRequest request(LocalDate from, LocalDate to, long budgetVnd) {
        GenerateTripRequest req = mock(GenerateTripRequest.class);
        when(req.getStartDate()).thenReturn(from);
        when(req.getEndDate()).thenReturn(to);
        when(req.getBudgetVnd()).thenReturn(budgetVnd);
        return req;
    }

    private AiItineraryResult itinerary(AiItineraryResult.AiDay... days) {
        AiItineraryResult r = new AiItineraryResult();
        r.setTitle("test");
        r.setDays(List.of(days));
        r.setChecklist(List.of(checklistItem("PAPERS", "CMND/CCCD")));
        return r;
    }

    private AiItineraryResult.AiChecklistItem checklistItem(String category, String name) {
        AiItineraryResult.AiChecklistItem c = new AiItineraryResult.AiChecklistItem();
        c.setCategory(category);
        c.setName(name);
        return c;
    }

    private AiItineraryResult.AiDay day(int dayNumber, String date, List<AiItineraryResult.AiActivity> activities) {
        AiItineraryResult.AiDay d = new AiItineraryResult.AiDay();
        d.setDayNumber(dayNumber);
        d.setDate(date);
        d.setActivities(activities);
        return d;
    }

    private AiItineraryResult.AiActivity activity(String time, String name, String type, String searchQuery, long costVnd) {
        AiItineraryResult.AiActivity a = new AiItineraryResult.AiActivity();
        a.setTime(time);
        a.setName(name);
        a.setDescription("desc");
        a.setType(type);
        a.setCostVnd(costVnd);
        a.setSearchQuery(searchQuery);
        return a;
    }
}
