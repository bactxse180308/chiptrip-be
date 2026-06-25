package com.tranbac.chiptripbe.module.trip.entity;

import com.tranbac.chiptripbe.common.entity.BaseEntity;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.time.LocalDateTime;

/**
 * Comment trên trip public. parentId = NULL là comment gốc, có giá trị là reply
 * (nested không giới hạn độ sâu). KHÔNG map @OneToMany children — load children
 * qua TripCommentRepository.findByParentIdOrderByCreatedAtAsc.
 */
@Entity
@Table(name = "trip_comments",
        indexes = {
                @Index(name = "ix_trip_comments_trip_created", columnList = "trip_id, created_at"),
                @Index(name = "ix_trip_comments_parent", columnList = "parent_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripComment extends BaseEntity {

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_trip_comments_trip"))
    private Trip trip;

    /**
     * FK NO ACTION tới users — giả định user chỉ bị soft-delete (isActive=false,
     * xem UserServiceImpl DELETE /users/me). Nếu sau này có hard-delete user,
     * phải xóa comment của user đó trước (hoặc đổi sang ON DELETE CASCADE).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_trip_comments_user"))
    private User user;

    @Column(name = "parent_id")
    private Long parentId;

    // sensitive — do not log
    @Nationalized
    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME2")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "DATETIME2")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
