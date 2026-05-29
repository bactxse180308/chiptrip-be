package com.tranbac.chiptripbe.module.ai.specification;

import com.tranbac.chiptripbe.module.ai.entity.AiUsage;
import org.springframework.data.jpa.domain.Specification;
import java.time.LocalDateTime;

public class AiUsageSpecification {

    public static Specification<AiUsage> withUserId(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    public static Specification<AiUsage> withProvider(String provider) {
        return (root, query, cb) -> cb.equal(root.get("provider"), provider);
    }

    public static Specification<AiUsage> createdAfter(LocalDateTime from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    public static Specification<AiUsage> createdBefore(LocalDateTime to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to);
    }
}
