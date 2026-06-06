package com.tranbac.chiptripbe.module.place.entity;

import com.tranbac.chiptripbe.common.entity.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "place_cache",
    indexes = {
        @Index(name = "ix_place_cache_normalized_name", columnList = "normalized_name"),
        @Index(name = "ix_place_cache_goong_place_id", columnList = "goong_place_id")
    })
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PlaceCache extends BaseAuditEntity {

    @Nationalized
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Tên đã chuẩn hóa (bỏ dấu, lowercase) dùng để tra cứu nhanh */
    @Column(name = "normalized_name", nullable = false, length = 255)
    private String normalizedName;

    @Nationalized
    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "goong_place_id", length = 255)
    private String goongPlaceId;

    /** data_id từ SerpApi, dùng để gọi lấy reviews/photos chi tiết hơn sau này */
    @Column(name = "serp_data_id", length = 255)
    private String serpDataId;

    @Column(name = "rating", precision = 3, scale = 1)
    private BigDecimal rating;

    @Column(name = "review_count")
    private Integer reviewCount;

    /** JSON array các khung giờ mở cửa: [{"day":"Monday","hours":"8:00 AM–9:00 PM"}, ...] */
    @Column(name = "opening_hours_json", columnDefinition = "NVARCHAR(MAX)")
    private String openingHoursJson;

    /** "OPEN" | "CLOSED" | null nếu không rõ */
    @Column(name = "open_state", length = 20)
    private String openState;

    /** JSON array ảnh: [{"url":"...", "thumbnail":"..."}, ...] */
    @Column(name = "photos_json", columnDefinition = "NVARCHAR(MAX)")
    private String photosJson;

    /** JSON array đánh giá thật: [{"author":"...", "avatar":"...", "rating":4.5, "time":"...", "text":"..."}, ...] */
    @Column(name = "reviews_json", columnDefinition = "NVARCHAR(MAX)")
    private String reviewsJson;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "website", length = 500)
    private String website;

    /** Thời điểm lần cuối đồng bộ Goong (geocode) thành công */
    @Column(name = "last_synced_at", columnDefinition = "DATETIME2")
    private LocalDateTime lastSyncedAt;

    /** Thời điểm lần cuối ATTEMPT gọi SerpApi (thành công hoặc không có data). Null = chưa từng thử. */
    @Column(name = "serp_synced_at", columnDefinition = "DATETIME2")
    private LocalDateTime serpSyncedAt;

    /** True khi SerpApi đã trả về data hữu ích (có rating HOẶC có ảnh). False = cần retry theo backoff. */
    @Column(name = "serp_enriched", columnDefinition = "bit NOT NULL DEFAULT 0")
    @Builder.Default
    private boolean serpEnriched = false;
}