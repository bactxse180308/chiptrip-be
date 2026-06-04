package com.tranbac.chiptripbe.module.trip.dto.response;

import com.tranbac.chiptripbe.common.enums.TripMemberRole;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
@Builder
public class TripMemberResponse {
    private Long id;
    private Long userId;       // null for guest members
    private String displayName;
    private String avatarUrl;  // null for guest members
    private TripMemberRole role;
    private LocalDateTime createdAt;
}
