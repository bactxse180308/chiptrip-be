package com.tranbac.chiptripbe.module.place.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlaceDto {

    private Long id;
    private String name;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;

    /** null nếu chưa có đánh giá */
    private BigDecimal rating;
    /** null nếu chưa có */
    private Integer reviewCount;

    /** "OPEN" | "CLOSED" | null nếu không rõ */
    private String openState;
    /** Danh sách khung giờ mở cửa theo ngày trong tuần */
    private List<OpeningHour> openingHours;

    private List<PlacePhoto> photos;
    private List<PlaceReview> reviews;
    private String phone;
    private String website;

    @Getter
    @Builder
    public static class OpeningHour {
        private String day;
        private String hours;
    }

    @Getter
    @Builder
    public static class PlacePhoto {
        private String url;
        private String thumbnail;
    }

    @Getter
    @Builder
    public static class PlaceReview {
        private String author;
        private String avatar;
        private BigDecimal rating;
        private String time;
        private String text;
    }
}