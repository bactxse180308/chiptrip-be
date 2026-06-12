package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.notification.event.TripCommentedEvent;
import com.tranbac.chiptripbe.module.notification.event.TripLikedEvent;
import com.tranbac.chiptripbe.module.trip.dto.request.AddCommentRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.LikeResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripCommentResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripPublicSummaryResponse;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripComment;
import com.tranbac.chiptripbe.module.trip.entity.TripLike;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripCommentRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripLikeRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripSocialService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TripSocialServiceImpl implements TripSocialService {

    private final TripRepository tripRepository;
    private final TripLikeRepository tripLikeRepository;
    private final TripCommentRepository tripCommentRepository;
    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public TripPublicSummaryResponse publishTrip(Long userId, Long tripId, boolean isPublic) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        if (!trip.getUser().getId().equals(userId)) {
            throw AppException.forbidden("Bạn không có quyền với chuyến đi này");
        }

        trip.setPublic(isPublic);
        if (isPublic && trip.getPublishedAt() == null) {
            trip.setPublishedAt(LocalDateTime.now());
        }
        trip = tripRepository.save(trip);
        log.info("Trip id={} set isPublic={} by userId={}", tripId, isPublic, userId);

        String thumbnail = findThumbnails(List.of(trip.getId())).get(trip.getId());
        return toPublicSummary(trip, thumbnail);
    }

    @Override
    @Transactional
    public LikeResponse toggleLike(Long userId, Long tripId) {
        Trip trip = findPublicTrip(tripId);

        boolean liked;
        if (tripLikeRepository.existsByTripIdAndUserId(tripId, userId)) {
            tripLikeRepository.deleteByTripIdAndUserId(tripId, userId);
            tripRepository.updateLikesCount(tripId, -1);
            liked = false;
        } else {
            tripLikeRepository.save(TripLike.builder().tripId(tripId).userId(userId).build());
            tripRepository.updateLikesCount(tripId, 1);
            liked = true;

            // Noti cho chủ trip (không tự noti khi like trip của chính mình)
            Long ownerId = trip.getUser().getId();
            if (!ownerId.equals(userId)) {
                String likerName = userRepository.findById(userId)
                        .map(User::getFullName).orElse(null);
                eventPublisher.publishEvent(
                        new TripLikedEvent(ownerId, tripId, trip.getTitle(), likerName));
            }
        }

        return LikeResponse.builder()
                .liked(liked)
                .likesCount(tripLikeRepository.countByTripId(tripId))
                .build();
    }

    @Override
    public LikeResponse getLikeStatus(Long userId, Long tripId) {
        findPublicTrip(tripId);
        return LikeResponse.builder()
                .liked(tripLikeRepository.existsByTripIdAndUserId(tripId, userId))
                .likesCount(tripLikeRepository.countByTripId(tripId))
                .build();
    }

    @Override
    @Transactional
    public TripCommentResponse addComment(Long userId, Long tripId, AddCommentRequest request) {
        Trip trip = findPublicTrip(tripId);

        TripComment parent = null;
        if (request.getParentId() != null) {
            parent = tripCommentRepository.findById(request.getParentId())
                    .orElseThrow(() -> AppException.notFound("Không tìm thấy comment cha"));
            if (!parent.getTripId().equals(tripId)) {
                throw AppException.badRequest("Comment cha không thuộc chuyến đi này");
            }
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));

        // @mention trong content không cần validate — FE tự render, BE lưu raw text
        TripComment comment = tripCommentRepository.save(TripComment.builder()
                .tripId(tripId)
                .user(user)
                .parentId(request.getParentId())
                .content(request.getContent())
                .build());
        tripRepository.updateCommentsCount(tripId, 1);

        // Noti: reply → tác giả comment cha; ngoài ra → chủ trip. Không tự noti chính mình,
        // không noti chủ trip 2 lần khi chủ trip cũng là người bị reply.
        Long ownerId = trip.getUser().getId();
        Long parentAuthorId = parent != null ? parent.getUser().getId() : null;
        String preview = abbreviate(request.getContent());
        if (parentAuthorId != null && !parentAuthorId.equals(userId)) {
            eventPublisher.publishEvent(new TripCommentedEvent(
                    parentAuthorId, tripId, trip.getTitle(), user.getFullName(), preview, true));
        }
        if (!ownerId.equals(userId) && !ownerId.equals(parentAuthorId)) {
            eventPublisher.publishEvent(new TripCommentedEvent(
                    ownerId, tripId, trip.getTitle(), user.getFullName(), preview, false));
        }

        return toCommentResponse(comment, List.of());
    }

    @Override
    @Transactional
    public void deleteComment(Long userId, Long tripId, Long commentId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        TripComment comment = tripCommentRepository.findById(commentId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy comment"));
        if (!comment.getTripId().equals(tripId)) {
            throw AppException.notFound("Comment không thuộc chuyến đi này");
        }

        boolean isAuthor = comment.getUser().getId().equals(userId);
        boolean isTripOwner = trip.getUser().getId().equals(userId);
        if (!isAuthor && !isTripOwner) {
            throw AppException.forbidden("Bạn không có quyền xóa comment này");
        }

        int deleted = deleteCommentSubtree(comment);
        tripRepository.updateCommentsCount(tripId, -deleted);
        log.info("Deleted comment id={} (+{} children) on trip id={} by userId={}",
                commentId, deleted - 1, tripId, userId);
    }

    @Override
    @Transactional
    public int adminDeleteComment(Long commentId) {
        TripComment comment = tripCommentRepository.findById(commentId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy comment"));
        int deleted = deleteCommentSubtree(comment);
        tripRepository.updateCommentsCount(comment.getTripId(), -deleted);
        log.info("Admin deleted comment id={} (+{} children) on trip id={}",
                commentId, deleted - 1, comment.getTripId());
        return deleted;
    }

    @Override
    @Transactional
    public void adminUnpublishTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        trip.setPublic(false);
        tripRepository.save(trip);
        log.info("Admin unpublished trip id={}", tripId);
    }

    @Override
    public Page<TripCommentResponse> getComments(Long tripId, Pageable pageable) {
        findPublicTrip(tripId);
        Page<TripComment> roots = tripCommentRepository
                .findByTripIdAndParentIdIsNullOrderByCreatedAtDesc(tripId, pageable);
        // Toàn bộ replies load 1 query, dựng tree in-memory — tránh N+1 theo độ sâu
        Map<Long, List<TripComment>> byParent = repliesByParent(tripId);
        return roots.map(root -> toCommentTree(root, byParent));
    }

    @Override
    public Page<TripPublicSummaryResponse> getPublicFeed(Pageable pageable, String destination, String sort) {
        boolean featured = "featured".equalsIgnoreCase(sort);
        boolean hasDestination = destination != null && !destination.isBlank();
        String destinationFilter = hasDestination ? destination.trim() : null;
        Page<Trip> page;
        if (featured) {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
            page = hasDestination
                    ? tripRepository.findByIsPublicTrueAndPublishedAtGreaterThanEqualAndDestinationContainingIgnoreCaseOrderByLikesCountDescPublishedAtDesc(
                            cutoff, destinationFilter, pageable)
                    : tripRepository.findByIsPublicTrueAndPublishedAtGreaterThanEqualOrderByLikesCountDescPublishedAtDesc(
                            cutoff, pageable);
        } else {
            page = hasDestination
                    ? tripRepository.findByIsPublicTrueAndDestinationContainingIgnoreCaseOrderByPublishedAtDesc(
                            destinationFilter, pageable)
                    : tripRepository.findByIsPublicTrueOrderByPublishedAtDesc(pageable);
        }

        List<Long> tripIds = page.getContent().stream().map(Trip::getId).toList();
        Map<Long, String> thumbnails = findThumbnails(tripIds);
        return page.map(trip -> toPublicSummary(trip, thumbnails.get(trip.getId())));
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private Trip findPublicTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        if (!trip.isPublic()) {
            // Trip private không lộ ra ngoài dù biết tripId — trả 404 thay vì 403
            throw AppException.notFound("Không tìm thấy chuyến đi");
        }
        return trip;
    }

    /**
     * Xóa comment + toàn bộ subtree. Load replies 1 query, gom theo tầng, xóa từ tầng sâu nhất lên
     * (an toàn với FK parent_id NO ACTION). Trả tổng số comment đã xóa. KHÔNG đụng counter — caller tự cập nhật.
     */
    private int deleteCommentSubtree(TripComment root) {
        Map<Long, List<TripComment>> byParent = repliesByParent(root.getTripId());
        List<List<Long>> levels = new ArrayList<>();
        List<TripComment> current = List.of(root);
        while (!current.isEmpty()) {
            levels.add(current.stream().map(TripComment::getId).toList());
            current = current.stream()
                    .flatMap(c -> byParent.getOrDefault(c.getId(), List.of()).stream())
                    .toList();
        }
        int deleted = 0;
        for (int i = levels.size() - 1; i >= 0; i--) {
            tripCommentRepository.deleteAllByIdInBatch(levels.get(i));
            deleted += levels.get(i).size();
        }
        return deleted;
    }

    /** Map parentId → replies (sắp theo createdAt asc), load toàn bộ trong 1 query. */
    private Map<Long, List<TripComment>> repliesByParent(Long tripId) {
        return tripCommentRepository.findByTripIdAndParentIdIsNotNullOrderByCreatedAtAsc(tripId)
                .stream()
                .collect(Collectors.groupingBy(TripComment::getParentId));
    }

    /** Dựng tree đệ quy từ map in-memory — không query thêm. */
    private TripCommentResponse toCommentTree(TripComment comment, Map<Long, List<TripComment>> byParent) {
        List<TripCommentResponse> children = byParent.getOrDefault(comment.getId(), List.of()).stream()
                .map(child -> toCommentTree(child, byParent))
                .toList();
        return toCommentResponse(comment, children);
    }

    private TripCommentResponse toCommentResponse(TripComment comment, List<TripCommentResponse> children) {
        return TripCommentResponse.builder()
                .id(comment.getId())
                .tripId(comment.getTripId())
                .parentId(comment.getParentId())
                .userId(comment.getUser().getId())
                .userName(comment.getUser().getFullName())
                .userAvatarUrl(comment.getUser().getAvatarUrl())
                .content(comment.getContent())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .children(children)
                .build();
    }

    private Map<Long, String> findThumbnails(List<Long> tripIds) {
        Map<Long, String> map = new HashMap<>();
        if (tripIds.isEmpty()) return map;
        for (Object[] row : activityRepository.findFirstImageUrlsForTrips(tripIds)) {
            Long tripId = row[0] instanceof Number ? ((Number) row[0]).longValue() : Long.parseLong(row[0].toString());
            map.putIfAbsent(tripId, row[1] != null ? row[1].toString() : null);
        }
        return map;
    }

    private TripPublicSummaryResponse toPublicSummary(Trip trip, String thumbnailUrl) {
        return TripPublicSummaryResponse.builder()
                .id(trip.getId())
                .title(trip.getTitle())
                .destination(trip.getDestination())
                .dateStart(trip.getDateStart())
                .dateEnd(trip.getDateEnd())
                .peopleCount(trip.getPeopleCount())
                .thumbnailUrl(thumbnailUrl)
                .ownerName(trip.getUser().getFullName())
                .ownerAvatarUrl(trip.getUser().getAvatarUrl())
                .likesCount(trip.getLikesCount())
                .commentsCount(trip.getCommentsCount())
                .styles(parseStyles(trip.getStyles()))
                .publishedAt(trip.getPublishedAt())
                .isPublic(trip.isPublic())
                .build();
    }

    /** Rút gọn content làm preview cho notification body. */
    private String abbreviate(String content) {
        if (content == null) return "";
        return content.length() <= 100 ? content : content.substring(0, 97) + "...";
    }

    private List<String> parseStyles(String stylesJson) {
        if (stylesJson == null || stylesJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(stylesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
