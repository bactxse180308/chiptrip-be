package com.tranbac.chiptripbe.module.auth.repository;

import com.tranbac.chiptripbe.module.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findAllByUserId(Long userId);
    long countByUserIdAndRevokedFalse(Long userId);
}