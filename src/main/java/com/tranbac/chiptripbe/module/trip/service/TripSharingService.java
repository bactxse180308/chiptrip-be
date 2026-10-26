package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.trip.dto.response.ShareTokenResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripReadOnlyResponse;

/** Boundary for share links and public read-only trip projections. */
public interface TripSharingService {
    ShareTokenResponse enableShare(Long userId, Long tripId);
    void disableShare(Long userId, Long tripId);
    TripReadOnlyResponse getSharedTrip(String shareToken);
    TripReadOnlyResponse getPublicTrip(Long tripId);
}
