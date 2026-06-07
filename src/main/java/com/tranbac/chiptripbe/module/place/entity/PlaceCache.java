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
        @Index(name = "ix_place_cache_normalized_name_destination",
                columnList = "normalized_name, normalized_destination"),
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

    /** Destination đã chuẩn hóa — kết hợp với normalizedName để tránh nhầm địa điểm giữa các tỉnh */
    @Column(name = "normalized_destination", length = 255)
    private String normalizedDestination;

    @Nationalized
    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "goong_place_id", length = 255)
    private String goongPlaceId;

    /** Tên tỉnh/thành từ Goong V2 compound.province (vd "Đà Nẵng", "Hà Nội"). */
    @Nationalized
    @Column(name = "province_name", length = 100)
    private String provinceName;

    /** Tên phường/xã từ Goong V2 compound.commune (vd "Hội An", "Bến Thành"). */
    @Nationalized
    @Column(name = "commune_name", length = 100)
    private String communeName;

    /** Booking link cho khách sạn (lấy từ SerpApi Google Hotels). Null nếu không phải accommodation. */
    @Column(name = "booking_url", length = 500)
    private String bookingUrl;

    /** Giá phòng/đêm (VNĐ) lấy từ SerpApi Google Hotels. Null nếu không phải accommodation hoặc fetch fail. */
    @Column(name = "price_per_night_vnd")
    private Long pricePerNightVnd;

    /** data_id từ SerpApi, dùng để gọi lấy reviews/photos chi tiết hơn sau này */
    @Column(name = "serp_data_id", length = 255)
    private String serpDataId;

    /** place_id của Google từ SerpApi (dạng ChIJ...) */
    @Column(name = "serp_place_id", length = 255)
    private String serpPlaceId;

    /** Tên hiển thị trên Google Maps trả về từ SerpApi (giúp debug + đối chiếu) */
    @Nationalized
    @Column(name = "serp_title", length = 255)
    private String serpTitle;

    /** Loại địa điểm theo Google Maps: "Restaurant", "Tourist attraction", ... */
    @Column(name = "place_type", length = 100)
    private String placeType;

    /** Chuỗi giờ mở cửa rút gọn (ví dụ "Open ⋅ Closes 10 PM"), thường lấy từ trường "hours" */
    @Nationalized
    @Column(name = "hours_text", length = 255)
    private String hoursText;

    @Column(name = "rating", precision = 3, scale = 1)
    private BigDecimal rating;

    @Column(name = "review_count")
    private Integer reviewCount;

    /** JSON object/array thô từ SerpApi field "operating_hours" hoặc "hours.schedule" */
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

    /**
     * True khi SerpApi đã trả về data đủ dùng để hiển thị Google Maps card:
     * basic info (rating/phone/website) + photos + (opening hours hoặc reviews).
     */
    @Column(name = "serp_enriched", columnDefinition = "bit NOT NULL DEFAULT 0")
    @Builder.Default
    private boolean serpEnriched = false;
}
