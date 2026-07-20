package com.tranbac.chiptripbe.module.place.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.module.geocoding.client.SerpApiClient;
import com.tranbac.chiptripbe.module.place.service.PlacePhotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlacePhotoRefreshWorker {

    private final PlacePhotoService photoService;
    private final SerpApiClient serpApiClient;
    private final ObjectMapper objectMapper;

    @Async("photoRefreshExecutor")
    public void refreshAsync(Long placeId) {
        if (!photoService.tryStartRefresh(placeId)) return;

        try {
            PlacePhotoService.PhotoRefreshSource source = photoService.getRefreshSource(placeId).orElse(null);
            if (source == null || source.serpDataId() == null || source.serpDataId().isBlank()) {
                log.warn("Photo refresh skipped: placeId={} has no SerpApi data_id", placeId);
                photoService.failRefresh(placeId);
                return;
            }

            List<Map<String, String>> photos = serpApiClient.fetchPhotos(source.serpDataId());
            if (photos == null || photos.isEmpty()) {
                log.warn("Photo refresh returned no photos: placeId={}", placeId);
                photoService.failRefresh(placeId);
                return;
            }

            photoService.completeRefresh(placeId, objectMapper.writeValueAsString(photos));
            log.info("Photo refresh completed: placeId={} photos={}", placeId, photos.size());
        } catch (Exception exception) {
            log.warn("Photo refresh failed: placeId={} error={}", placeId, exception.getMessage());
            photoService.failRefresh(placeId);
        }
    }
}
