package com.tranbac.chiptripbe.module.user.specification;

import com.tranbac.chiptripbe.module.user.entity.User;
import org.springframework.data.jpa.domain.Specification;

public final class UserSpecification {

    private UserSpecification() {}

    public static Specification<User> withSearch(String search) {
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("email")), pattern),
                cb.like(cb.lower(root.get("fullName")), pattern)
        );
    }

    public static Specification<User> withIsActive(Boolean isActive) {
        return (root, query, cb) -> cb.equal(root.get("isActive"), isActive);
    }

    public static Specification<User> withRole(String roleName) {
        return (root, query, cb) -> cb.equal(root.join("role").get("name"), roleName);
    }
}