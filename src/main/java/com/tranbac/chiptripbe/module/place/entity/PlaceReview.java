package com.tranbac.chiptripbe.module.place.entity;

import com.tranbac.chiptripbe.common.entity.BaseEntity;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.time.LocalDateTime;

/** Đánh giá ChipTrip do user viết — tách biệt với reviews Google (PlaceCache.reviewsJson). */
@Entity
@Table(name = "place_reviews",
        uniqueConstraints = @UniqueConstraint(name = "uk_place_reviews_place_user",
                columnNames = {"place_cache_id", "user_id"}),
        indexes = @Index(name = "ix_place_reviews_place", columnList = "place_cache_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaceReview extends BaseEntity {

    @Column(name = "place_cache_id", nullable = false)
    private Long placeCacheId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_cache_id", nullable = false, insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_place_reviews_place_cache"))
    private PlaceCache placeCache;

    /**
     * FK NO ACTION tới users — giả định user chỉ bị soft-delete (isActive=false).
     * Nếu sau này có hard-delete user, phải xóa review của user đó trước.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_place_reviews_user"))
    private User user;

    @Column(name = "rating", nullable = false, columnDefinition = "TINYINT")
    private Integer rating;

    // sensitive — do not log
    @Nationalized
    @Column(name = "content", length = 500)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME2")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
