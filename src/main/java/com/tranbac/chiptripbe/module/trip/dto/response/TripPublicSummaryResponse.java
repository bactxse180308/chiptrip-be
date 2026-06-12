package com.tranbac.chiptripbe.module.trip.dto.response;

import lombok.Builder;
import lombok.Getter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Card trip public trong feed Khám phá. */
@Getter
@Builder
public class TripPublicSummaryResponse {
    private Long id;
    private String title;
    private String destination;
    private LocalDate dateStart;
    private LocalDate dateEnd;
    private Integer peopleCount;
    /** imageUrl của activity đầu tiên có ảnh. */
    private String thumbnailUrl;
    private String ownerName;
    private String ownerAvatarUrl;
    private Integer likesCount;
    private Integer commentsCount;
    private List<String> styles;
    private LocalDateTime publishedAt;
    /** Boolean (không phải boolean) để Jackson xuất đúng tên "isPublic" thay vì "public". */
    private Boolean isPublic;
}
