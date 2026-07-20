package com.tranbac.chiptripbe.module.place.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.module.place.dto.PhotoFailureReportRequest;
import com.tranbac.chiptripbe.module.place.service.PlacePhotoService;
import com.tranbac.chiptripbe.module.place.service.PlaceQueryService;
import com.tranbac.chiptripbe.module.place.service.impl.PlacePhotoRefreshWorker;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaceControllerTest {

    @Test
    void reportPhotoFailure_acceptedReportSchedulesAsyncRefresh() {
        PlacePhotoService photoService = mock(PlacePhotoService.class);
        PlacePhotoRefreshWorker worker = mock(PlacePhotoRefreshWorker.class);
        PlaceController controller = new PlaceController(mock(PlaceQueryService.class), photoService, worker);
        when(photoService.reportFailure(7L, "https://img.test/broken.jpg")).thenReturn(true);

        ResponseEntity<ApiResponse<Void>> response = controller.reportPhotoFailure(
                7L, new PhotoFailureReportRequest("https://img.test/broken.jpg"));

        assertEquals(202, response.getStatusCode().value());
        verify(worker).refreshAsync(7L);
    }

    @Test
    void reportPhotoFailure_duplicateReportDoesNotScheduleAnotherWorker() {
        PlacePhotoService photoService = mock(PlacePhotoService.class);
        PlacePhotoRefreshWorker worker = mock(PlacePhotoRefreshWorker.class);
        PlaceController controller = new PlaceController(mock(PlaceQueryService.class), photoService, worker);
        when(photoService.reportFailure(7L, "https://img.test/broken.jpg")).thenReturn(false);

        controller.reportPhotoFailure(7L, new PhotoFailureReportRequest("https://img.test/broken.jpg"));

        verify(worker, never()).refreshAsync(7L);
    }
}
