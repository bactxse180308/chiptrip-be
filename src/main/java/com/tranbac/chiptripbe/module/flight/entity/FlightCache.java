package com.tranbac.chiptripbe.module.flight.entity;

import com.tranbac.chiptripbe.common.entity.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Cache kết quả Google Flights theo route+ngày+số khách.
 * Giá vé đổi hàng ngày nên TTL ngắn (xem FlightServiceImpl), dùng updatedAt để check freshness.
 */
@Entity
@Table(name = "flight_cache",
        indexes = { @Index(name = "ix_flight_cache_route_key", columnList = "route_key", unique = true) })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightCache extends BaseAuditEntity {

    /** Key: depId_arrId_outDate_(retDate|OW)_adults */
    @Column(name = "route_key", nullable = false, length = 100, unique = true)
    private String routeKey;

    /** JSON serialize của FlightSuggestionResponse */
    @Column(name = "payload_json", columnDefinition = "NVARCHAR(MAX)")
    private String payloadJson;
}
