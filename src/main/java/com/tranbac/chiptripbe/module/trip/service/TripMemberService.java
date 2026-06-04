package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.trip.dto.request.AddMemberRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateMemberRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripMemberResponse;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.user.entity.User;

import java.util.List;

public interface TripMemberService {

    List<TripMemberResponse> getMembers(Long requesterId, Long tripId);

    TripMemberResponse addMember(Long ownerId, Long tripId, AddMemberRequest request);

    TripMemberResponse updateMember(Long ownerId, Long tripId, Long memberId, UpdateMemberRequest request);

    void removeMember(Long ownerId, Long tripId, Long memberId);

    /** Called internally when a trip is created or cloned — seeds the OWNER member row. */
    void seedOwner(Trip trip, User owner);
}
