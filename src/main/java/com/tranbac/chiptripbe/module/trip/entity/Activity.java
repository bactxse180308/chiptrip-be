package com.tranbac.chiptripbe.module.trip.entity;

import com.tranbac.chiptripbe.common.entity.BaseEntity;
import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.math.BigDecimal;
import java.time.LocalTime;

@Entity
@Table(name = "activities",
        indexes = @Index(name = "ix_activities_day_order", columnList = "day_id, display_order"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Activity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "day_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_activities_day"))
    private TripDay day;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Nationalized
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Nationalized
    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private ActivityType type;

    @Column(name = "cost_vnd", nullable = false)
    @Builder.Default
    private Long costVnd = 0L;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "booking_url", length = 500)
    private String bookingUrl;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Nationalized
    @Column(name = "search_query", length = 300)
    private String searchQuery;

    @Column(name = "place_id", length = 500)
    private String placeId;

    @Nationalized
    @Column(name = "formatted_address", length = 500)
    private String formattedAddress;

    @Column(name = "geocoding_provider", length = 30)
    private String geocodingProvider;

    /** FK sang place_cache.id — null nếu không geocode được */
    @Column(name = "place_cache_id")
    private Long placeCacheId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_cache_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_activities_place_cache"))
    private PlaceCache placeCache;
}
