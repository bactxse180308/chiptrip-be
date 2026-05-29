package com.tranbac.chiptripbe.module.trip.specification;

import com.tranbac.chiptripbe.module.trip.entity.Trip;
import org.springframework.data.jpa.domain.Specification;
import java.time.LocalDateTime;

public class TripSpecification {

    public static Specification<Trip> withUserId(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    public static Specification<Trip> createdAfter(LocalDateTime from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    public static Specification<Trip> createdBefore(LocalDateTime to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to);
    }
}
