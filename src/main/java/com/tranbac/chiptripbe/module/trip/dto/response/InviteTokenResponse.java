package com.tranbac.chiptripbe.module.trip.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InviteTokenResponse {
    private String inviteToken;
}
