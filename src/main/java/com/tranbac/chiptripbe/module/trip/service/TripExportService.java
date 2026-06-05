package com.tranbac.chiptripbe.module.trip.service;

public interface TripExportService {

    /** Render trip thành PDF (đã kiểm tra quyền sở hữu qua TripService.getTripDetail). */
    byte[] exportPdf(Long userId, Long tripId);
}
