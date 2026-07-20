package com.tranbac.chiptripbe.module.place.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.enums.PhotoSyncStatus;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlacePhotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
class PlacePhotoServiceImpl implements PlacePhotoService {

    private final PlaceCacheRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public boolean reportFailure(Long placeId, String failedUrl) {
        PlaceCache place = findPlace(placeId);
        String normalizedUrl = failedUrl == null ? null : failedUrl.trim();
        if (normalizedUrl == null || normalizedUrl.isBlank()) return false;

        List<Map<String, String>> photos = parsePhotos(place.getPhotosJson());
        if (photos == null) return false;

        List<Map<String, String>> remaining = new ArrayList<>(photos);
        boolean removed = remaining.removeIf(photo ->
                Objects.equals(normalizedUrl, photo.get("url"))
                        || Objects.equals(normalizedUrl, photo.get("thumbnail")));
        if (!removed) return false;

        place.setPhotosJson(writePhotosOrNull(remaining));
        place.setPhotoFailureCount(place.getPhotoFailureCount() + 1);
        // Bao loi den trong luc worker dang chay khong duoc ha trang thai, tranh worker khac claim trung.
        if (place.getPhotosStatus() != PhotoSyncStatus.REFRESHING) {
            place.setPhotosStatus(PhotoSyncStatus.STALE);
        }
        repository.save(place);
        log.info("Place photo marked broken: placeId={} remaining={} failures={}",
                placeId, remaining.size(), place.getPhotoFailureCount());
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryStartRefresh(Long placeId) {
        return repository.claimPhotoRefresh(placeId,
                PhotoSyncStatus.REFRESHING,
                PhotoSyncStatus.STALE,
                PhotoSyncStatus.FAILED) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PhotoRefreshSource> getRefreshSource(Long placeId) {
        return repository.findById(placeId)
                .map(place -> new PhotoRefreshSource(place.getId(), place.getSerpDataId()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRefresh(Long placeId, String photosJson) {
        PlaceCache place = findPlace(placeId);
        place.setPhotosJson(photosJson);
        place.setPhotosSyncedAt(LocalDateTime.now());
        place.setPhotosStatus(PhotoSyncStatus.READY);
        place.setPhotoFailureCount(0);
        repository.save(place);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRefresh(Long placeId) {
        PlaceCache place = findPlace(placeId);
        place.setPhotosStatus(PhotoSyncStatus.FAILED);
        place.setPhotoFailureCount(place.getPhotoFailureCount() + 1);
        repository.save(place);
    }

    private PlaceCache findPlace(Long placeId) {
        return repository.findById(placeId)
                .orElseThrow(() -> AppException.notFound("Khong tim thay dia diem"));
    }

    private List<Map<String, String>> parsePhotos(String photosJson) {
        if (photosJson == null || photosJson.isBlank()) return null;
        try {
            return objectMapper.readValue(photosJson, new TypeReference<>() { });
        } catch (Exception exception) {
            log.warn("Cannot parse photos_json while reporting failure: {}", exception.getMessage());
            return null;
        }
    }

    private String writePhotosOrNull(List<Map<String, String>> photos) {
        if (photos.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(photos);
        } catch (Exception exception) {
            throw new IllegalStateException("Khong the cap nhat danh sach anh", exception);
        }
    }
}
