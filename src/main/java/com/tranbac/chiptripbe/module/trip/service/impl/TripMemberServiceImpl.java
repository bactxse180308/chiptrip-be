package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.common.enums.TripMemberRole;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.trip.dto.request.AddMemberRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateMemberRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripMemberResponse;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripMember;
import com.tranbac.chiptripbe.module.trip.repository.TripMemberRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripMemberService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TripMemberServiceImpl implements TripMemberService {

    private final TripMemberRepository tripMemberRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;

    @Override
    public List<TripMemberResponse> getMembers(Long requesterId, Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        boolean isOwner = trip.getUser().getId().equals(requesterId);
        boolean isMember = tripMemberRepository.existsByTripIdAndUserId(tripId, requesterId);
        if (!isOwner && !isMember) {
            throw new AppException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Bạn không phải thành viên chuyến đi này", null);
        }
        return tripMemberRepository.findByTripIdWithUser(tripId).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public TripMemberResponse addMember(Long ownerId, Long tripId, AddMemberRequest request) {
        Trip trip = findTripByOwner(tripId, ownerId);

        if (request.getUserId() == null
                && (request.getDisplayName() == null || request.getDisplayName().isBlank())) {
            throw AppException.badRequest("Phải cung cấp userId hoặc displayName");
        }

        User linkedUser = null;
        String displayName;

        if (request.getUserId() != null) {
            linkedUser = userRepository.findById(request.getUserId())
                    .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));
            if (tripMemberRepository.existsByTripIdAndUserId(tripId, linkedUser.getId())) {
                throw new AppException(HttpStatus.CONFLICT, "ALREADY_A_MEMBER", "Người dùng này đã là thành viên", null);
            }
            displayName = (request.getDisplayName() != null && !request.getDisplayName().isBlank())
                    ? request.getDisplayName().trim()
                    : linkedUser.getFullName();
        } else {
            displayName = request.getDisplayName().trim();
        }

        TripMember member = TripMember.builder()
                .trip(trip)
                .user(linkedUser)
                .displayName(displayName)
                .role(TripMemberRole.MEMBER)
                .build();
        return toResponse(tripMemberRepository.save(member));
    }

    @Override
    @Transactional
    public TripMemberResponse updateMember(Long ownerId, Long tripId, Long memberId, UpdateMemberRequest request) {
        findTripByOwner(tripId, ownerId);
        TripMember member = tripMemberRepository.findById(memberId)
                .filter(m -> m.getTrip().getId().equals(tripId))
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Không tìm thấy thành viên", null));
        member.setDisplayName(request.getDisplayName().trim());
        return toResponse(tripMemberRepository.save(member));
    }

    @Override
    @Transactional
    public void removeMember(Long ownerId, Long tripId, Long memberId) {
        findTripByOwner(tripId, ownerId);
        TripMember member = tripMemberRepository.findById(memberId)
                .filter(m -> m.getTrip().getId().equals(tripId))
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Không tìm thấy thành viên", null));
        if (member.getRole() == TripMemberRole.OWNER) {
            throw new AppException(HttpStatus.FORBIDDEN, "CANNOT_REMOVE_OWNER", "Không thể xóa chủ chuyến đi", null);
        }
        tripMemberRepository.delete(member);
    }

    @Override
    @Transactional
    public void seedOwner(Trip trip, User owner) {
        if (!tripMemberRepository.existsByTripIdAndUserId(trip.getId(), owner.getId())) {
            tripMemberRepository.save(TripMember.builder()
                    .trip(trip)
                    .user(owner)
                    .displayName(owner.getFullName())
                    .role(TripMemberRole.OWNER)
                    .build());
        }
    }

    private Trip findTripByOwner(Long tripId, Long ownerId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        if (!trip.getUser().getId().equals(ownerId)) {
            throw new AppException(HttpStatus.FORBIDDEN, "NOT_TRIP_OWNER", "Bạn không phải chủ chuyến đi", null);
        }
        return trip;
    }

    private TripMemberResponse toResponse(TripMember m) {
        return TripMemberResponse.builder()
                .id(m.getId())
                .userId(m.getUser() != null ? m.getUser().getId() : null)
                .displayName(m.getDisplayName())
                .avatarUrl(m.getUser() != null ? m.getUser().getAvatarUrl() : null)
                .role(m.getRole())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
