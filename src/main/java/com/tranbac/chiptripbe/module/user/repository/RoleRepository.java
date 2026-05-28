package com.tranbac.chiptripbe.module.user.repository;

import com.tranbac.chiptripbe.module.user.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(String name);
}