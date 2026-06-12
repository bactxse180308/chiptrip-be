package com.tranbac.chiptripbe.module.flight.repository;

import com.tranbac.chiptripbe.module.flight.entity.FlightCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FlightCacheRepository extends JpaRepository<FlightCache, Long> {
    Optional<FlightCache> findByRouteKey(String routeKey);
}
