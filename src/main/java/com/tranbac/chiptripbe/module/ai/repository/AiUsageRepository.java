package com.tranbac.chiptripbe.module.ai.repository;

import com.tranbac.chiptripbe.module.ai.entity.AiUsage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {
    Page<AiUsage> findByUserId(Long userId, Pageable pageable);
}