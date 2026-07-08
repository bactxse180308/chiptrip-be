package com.tranbac.chiptripbe.module.trip.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

import java.time.LocalDate;

@Getter
public class GenerateTripRequest {

    @NotBlank(message = "Điểm đi không được trống")
    @Nationalized
    private String departure;

    @NotBlank(message = "Điểm đến không được trống")
    @Nationalized
    private String destination;

    @NotNull(message = "Ngày bắt đầu không được trống")
    private LocalDate startDate;

    @NotNull(message = "Ngày kết thúc không được trống")
    private LocalDate endDate;

    @NotNull(message = "Số người không được trống")
    @Min(value = 1, message = "Số người tối thiểu là 1")
    private Integer peopleCount;

    @NotNull(message = "Ngân sách không được trống")
    @Min(value = 0, message = "Ngân sách không được âm")
    private Long budgetVnd;

    private java.util.List<String> styles;

    /**
     * "roundtrip" | "oneway" (nullable). Ảnh hưởng có/không chặng di chuyển VỀ ở ngày cuối.
     * Null → coi như khứ hồi (mặc định an toàn cho client cũ chưa gửi trường này).
     */
    private String tripType;

    /** Giờ khởi hành ngày đầu "HH:mm" (nullable) — để AI xếp lịch ngày 1 thực tế theo giờ tới nơi. */
    private String departureTime;

    /** Giờ về dự kiến ngày cuối "HH:mm" (nullable) — chỉ dùng khi khứ hồi. */
    private String returnTime;

    /** Số vé di chuyển (nullable) — mặc định theo số người nếu trống. */
    @Min(value = 1, message = "Số vé tối thiểu là 1")
    private Integer tickets;
}
