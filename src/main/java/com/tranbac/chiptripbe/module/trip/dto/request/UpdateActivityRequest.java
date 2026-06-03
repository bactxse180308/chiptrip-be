package com.tranbac.chiptripbe.module.trip.dto.request;

import com.tranbac.chiptripbe.common.enums.ActivityType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.hibernate.annotations.Nationalized;

import java.math.BigDecimal;
import java.time.LocalTime;

@Getter
public class UpdateActivityRequest {

    private LocalTime startTime;

    @Nationalized
    private String name;

    @Nationalized
    private String description;

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
