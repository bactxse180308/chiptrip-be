package com.tranbac.chiptripbe.module.ai.repository;

import com.tranbac.chiptripbe.module.ai.entity.AiUsage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {
    Page<AiUsage> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT COUNT(a), COALESCE(SUM(a.tokensIn), 0), COALESCE(SUM(a.tokensOut), 0), COALESCE(SUM(a.costUsd), 0) " +
           "FROM AiUsage a WHERE a.user.id = :userId")
    Object[] aggregateByUserId(@Param("userId") Long userId);
}