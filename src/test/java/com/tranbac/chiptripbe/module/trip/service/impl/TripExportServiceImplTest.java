package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.service.TripService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** Mục 5.7: Export PDF gate theo trip.createdAsPremium (snapshot lúc tạo). */
@ExtendWith(MockitoExtension.class)
class TripExportServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long TRIP_ID = 9L;

    @Mock private TripService tripService;

    @InjectMocks private TripExportServiceImpl exportService;

    @Test
    void exportPdf_normalTrip_throwsPremiumRequired() {
        when(tripService.getTripDetail(USER_ID, TRIP_ID))
                .thenReturn(TripDetailResponse.builder().id(TRIP_ID).createdAsPremium(false).build());

        AppException ex = assertThrows(AppException.class, () -> exportService.exportPdf(USER_ID, TRIP_ID));

        assertEquals("PREMIUM_REQUIRED", ex.getCode());
    }
}
