package com.tranbac.chiptripbe.module.trip.service;

/** Internal maintenance contract; not part of user-facing trip operations. */
public interface TripEnrichmentMaintenance {
    void sweepPendingEnrichments();
}
