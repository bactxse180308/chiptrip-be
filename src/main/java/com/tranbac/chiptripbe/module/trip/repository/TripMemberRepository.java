package com.tranbac.chiptripbe.module.trip.repository;

import com.tranbac.chiptripbe.module.trip.entity.TripMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TripMemberRepository extends JpaRepository<TripMember, Long> {

    @Query("SELECT m FROM TripMember m LEFT JOIN FETCH m.user WHERE m.trip.id = :tripId ORDER BY m.createdAt ASC")
    List<TripMember> findByTripIdWithUser(@Param("tripId") Long tripId);

    boolean existsByTripIdAndUserId(Long tripId, Long userId);

    Optional<TripMember> findByTripIdAndUserId(Long tripId, Long userId);

    long countByTripId(Long tripId);
}
