package com.tranbac.chiptripbe.module.trip.dto.response;

import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class TripCommentResponse {
    private Long id;
    private Long tripId;
    private Long parentId;
    private Long userId;
    private String userName;
    private String userAvatarUrl;
    /** Raw text — @mention do FE tự render, BE chỉ lưu nguyên string. */
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** Replies — load đệ quy full tree từ root. */
    private List<TripCommentResponse> children;
}
