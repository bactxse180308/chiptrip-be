package com.tranbac.chiptripbe.module.trip.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LikeResponse {
    /** Trạng thái sau action: true = vừa like, false = vừa unlike. */
    private boolean liked;
    private long likesCount;
}
