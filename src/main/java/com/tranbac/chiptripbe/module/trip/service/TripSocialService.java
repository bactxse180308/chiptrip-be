package com.tranbac.chiptripbe.module.trip.service;

import com.tranbac.chiptripbe.module.trip.dto.request.AddCommentRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.LikeResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripCommentResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripPublicSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TripSocialService {

    TripPublicSummaryResponse publishTrip(Long userId, Long tripId, boolean isPublic);

    LikeResponse toggleLike(Long userId, Long tripId);

    /** Trạng thái like hiện tại của user với trip — FE dùng để render nút tim ban đầu. */
    LikeResponse getLikeStatus(Long userId, Long tripId);

    TripCommentResponse addComment(Long userId, Long tripId, AddCommentRequest request);

    void deleteComment(Long userId, Long tripId, Long commentId);

    /** Admin moderation: xóa comment (+ replies) bất kể tác giả. Trả số comment đã xóa. */
    int adminDeleteComment(Long commentId);

    /** Admin moderation: gỡ công khai trip vi phạm. */
    void adminUnpublishTrip(Long tripId);

    Page<TripCommentResponse> getComments(Long tripId, Pageable pageable);

    Page<TripPublicSummaryResponse> getPublicFeed(Pageable pageable, String destination, String sort);
}
