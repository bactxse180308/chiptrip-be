package com.tranbac.chiptripbe.module.trip.repository;

import com.tranbac.chiptripbe.module.trip.entity.Trip;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long>, JpaSpecificationExecutor<Trip> {

    Page<Trip> findByUserId(Long userId, Pageable pageable);

    Optional<Trip> findByShareToken(String shareToken);

    Optional<Trip> findByIdAndUserId(Long id, Long userId);

    @Query("SELECT YEAR(t.createdAt), MONTH(t.createdAt), DAY(t.createdAt), COUNT(t) " +
           "FROM Trip t WHERE t.createdAt >= :from AND t.createdAt <= :to " +
           "GROUP BY YEAR(t.createdAt), MONTH(t.createdAt), DAY(t.createdAt) " +
           "ORDER BY YEAR(t.createdAt), MONTH(t.createdAt), DAY(t.createdAt)")
    List<Object[]> countCreationsByDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}