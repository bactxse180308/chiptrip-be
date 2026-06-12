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

    /** Tạo (hoặc trả lại nếu đã có) invite token — owner only. Idempotent để link cũ không chết. */
    String createInvite(Long ownerId, Long tripId);

    /** Thu hồi invite token (link mời cũ vô hiệu) — owner only. */
    void revokeInvite(Long ownerId, Long tripId);

    /** Preview tối thiểu cho trang join — public theo token. */
    com.tranbac.chiptripbe.module.trip.dto.response.TripInvitePreviewResponse getInvitePreview(String inviteToken);

    /** User tự tham gia làm MEMBER qua link mời. 409 nếu đã là thành viên. */
    TripMemberResponse joinByInvite(Long userId, String inviteToken);
}
