package com.tranbac.chiptripbe.module.trip.repository;

import com.tranbac.chiptripbe.module.trip.entity.ChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {

    java.util.List<ChecklistItem> findByTripIdOrderByDisplayOrder(Long tripId);

    @Query("SELECT c FROM ChecklistItem c WHERE c.id = :id AND c.trip.user.id = :userId")
    Optional<ChecklistItem> findByIdAndTripUserId(@Param("id") Long id, @Param("userId") Long userId);
}
