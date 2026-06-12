package com.tranbac.chiptripbe.module.place.dto;

import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
@Builder
public class PlaceReviewResponse {
    private Long id;
    private Long userId;
    private String userName;
    private String userAvatarUrl;
    private Integer rating;
    private String content;
    private LocalDateTime createdAt;
}
