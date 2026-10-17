package com.tranbac.chiptripbe.module.trip.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.AssertTrue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

import java.time.LocalDate;

@Getter
public class GenerateTripRequest {

    @NotBlank(message = "Điểm đi không được trống")
    @Size(max = 150)
    @Nationalized
    private String departure;

    @NotBlank(message = "Điểm đến không được trống")
    @Size(max = 150)
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

    @Size(max = 10)
    private List<@jakarta.validation.constraints.NotBlank @Size(max = 60) String> styles;

    /**
     * "roundtrip" | "oneway" (nullable). Ảnh hưởng có/không chặng di chuyển VỀ ở ngày cuối.
     * Null → coi như khứ hồi (mặc định an toàn cho client cũ chưa gửi trường này).
     */
    @Pattern(regexp = "(?i:roundtrip|oneway)")
    private String tripType;

    /** Giờ khởi hành ngày đầu "HH:mm" (nullable) — để AI xếp lịch ngày 1 thực tế theo giờ tới nơi. */
    @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]")
    private String departureTime;

    /** Giờ về dự kiến ngày cuối "HH:mm" (nullable) — chỉ dùng khi khứ hồi. */
    @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]")
    private String returnTime;

    /** Số vé di chuyển (nullable) — mặc định theo số người nếu trống. */
    @Min(value = 1, message = "Số vé tối thiểu là 1")
    private Integer tickets;

    private static final ObjectMapper STYLES_MAPPER = new ObjectMapper();

    @JsonIgnore
    @AssertTrue(message = "styles vượt quá 500 ký tự JSON lưu trữ")
    public boolean isStylesWithinStorageLimit() {
        if (styles == null) return true;
        try {
            return STYLES_MAPPER.writeValueAsString(styles).length() <= 500;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }
}
