package com.tranbac.chiptripbe.module.place.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.module.geocoding.client.SerpApiClient;
import com.tranbac.chiptripbe.module.place.service.PlacePhotoService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlacePhotoRefreshWorkerTest {

    @Test
    void refreshAsync_fetchesNewPhotosAndCompletesRefresh() {
        PlacePhotoService photoService = mock(PlacePhotoService.class);
        SerpApiClient serpApiClient = mock(SerpApiClient.class);
        PlacePhotoRefreshWorker worker = new PlacePhotoRefreshWorker(photoService, serpApiClient, new ObjectMapper());
        when(photoService.tryStartRefresh(7L)).thenReturn(true);
        when(photoService.getRefreshSource(7L)).thenReturn(Optional.of(
                new PlacePhotoService.PhotoRefreshSource(7L, "0x123:0x456")));
        when(serpApiClient.fetchPhotos("0x123:0x456")).thenReturn(List.of(
                Map.of("url", "https://img.test/fresh.jpg", "thumbnail", "https://img.test/fresh-thumb.jpg")));

        worker.refreshAsync(7L);

        verify(photoService).completeRefresh(org.mockito.ArgumentMatchers.eq(7L), contains("fresh.jpg"));
        verify(photoService, never()).failRefresh(7L);
    }

    @Test
    void refreshAsync_emptyProviderResultMarksRefreshFailed() {
        PlacePhotoService photoService = mock(PlacePhotoService.class);
        SerpApiClient serpApiClient = mock(SerpApiClient.class);
        PlacePhotoRefreshWorker worker = new PlacePhotoRefreshWorker(photoService, serpApiClient, new ObjectMapper());
        when(photoService.tryStartRefresh(7L)).thenReturn(true);
        when(photoService.getRefreshSource(7L)).thenReturn(Optional.of(
                new PlacePhotoService.PhotoRefreshSource(7L, "0x123:0x456")));
        when(serpApiClient.fetchPhotos("0x123:0x456")).thenReturn(List.of());

        worker.refreshAsync(7L);

        verify(photoService).failRefresh(7L);
        verify(photoService, never()).completeRefresh(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.anyString());
    }
}
