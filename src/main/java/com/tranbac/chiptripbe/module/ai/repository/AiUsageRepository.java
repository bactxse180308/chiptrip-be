package com.tranbac.chiptripbe.module.ai.repository;

import com.tranbac.chiptripbe.module.ai.entity.AiUsage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long>, JpaSpecificationExecutor<AiUsage> {

    Page<AiUsage> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT COUNT(a), COALESCE(SUM(a.tokensIn), 0), COALESCE(SUM(a.tokensOut), 0), COALESCE(SUM(a.costUsd), 0) " +
           "FROM AiUsage a WHERE a.user.id = :userId")
    Object[] aggregateByUserId(@Param("userId") Long userId);

    @Query("SELECT a.provider, YEAR(a.createdAt), MONTH(a.createdAt), COUNT(a), " +
           "SUM(a.tokensIn), SUM(a.tokensOut), SUM(a.costUsd) " +
           "FROM AiUsage a WHERE a.createdAt >= :from AND a.createdAt <= :to " +
           "GROUP BY a.provider, YEAR(a.createdAt), MONTH(a.createdAt) " +
           "ORDER BY YEAR(a.createdAt), MONTH(a.createdAt), a.provider")
    List<Object[]> aggregateCostByProviderMonth(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(a.costUsd), 0), COUNT(a) " +
           "FROM AiUsage a WHERE a.createdAt >= :from AND a.createdAt <= :to")
    Object[] aggregateForPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}