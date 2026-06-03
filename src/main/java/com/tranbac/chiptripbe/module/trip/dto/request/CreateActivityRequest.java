package com.tranbac.chiptripbe.module.trip.dto.request;

import com.tranbac.chiptripbe.common.enums.ActivityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

import java.math.BigDecimal;
import java.time.LocalTime;

@Getter
public class CreateActivityRequest {

    @NotNull(message = "Thời gian không được trống")
    private LocalTime startTime;

    @NotBlank(message = "Tên hoạt động không được trống")
    @Nationalized
    private String name;

    @Nationalized
    private String description;

    @NotNull(message = "Loại hoạt động không được trống")
    private ActivityType type;

    @jakarta.validation.constraints.Min(value = 0, message = "Chi phí không được âm")
    private Long costVnd;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private String imageUrl;

    private String bookingUrl;

    @jakarta.validation.constraints.Min(value = 0, message = "Thứ tự hiển thị không được âm")
    private Integer displayOrder;
}
