package com.tranbac.chiptripbe.module.place.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.enums.PhotoSyncStatus;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlacePhotoServiceImplTest {

    @Mock
    private PlaceCacheRepository repository;

    private PlacePhotoServiceImpl service;
    private PlaceCache place;

    @BeforeEach
    void setUp() {
        service = new PlacePhotoServiceImpl(repository, new ObjectMapper());
        place = PlaceCache.builder()
                .name("Dinh Doc Lap")
                .normalizedName("dinh doc lap")
                .photosJson("[{\"url\":\"https://img.test/broken.jpg\",\"thumbnail\":\"https://img.test/broken-thumb.jpg\"}," +
                        "{\"url\":\"https://img.test/good.jpg\",\"thumbnail\":\"https://img.test/good-thumb.jpg\"}]")
                .photosStatus(PhotoSyncStatus.READY)
                .photoFailureCount(0)
                .build();
        place.setId(7L);
        lenient().when(repository.findById(7L)).thenReturn(Optional.of(place));
    }

    @Test
    void reportFailure_removesOnlyMatchingPhotoAndMarksCacheStale() {
        boolean accepted = service.reportFailure(7L, "https://img.test/broken.jpg");

        assertTrue(accepted);
        assertFalse(place.getPhotosJson().contains("broken.jpg"));
        assertTrue(place.getPhotosJson().contains("good.jpg"));
        assertEquals(PhotoSyncStatus.STALE, place.getPhotosStatus());
        assertEquals(1, place.getPhotoFailureCount());
    }

    @Test
    void reportFailure_unknownUrlDoesNotMutateOrScheduleRefresh() {
        String originalJson = place.getPhotosJson();

        boolean accepted = service.reportFailure(7L, "https://other.test/not-in-cache.jpg");

        assertFalse(accepted);
        assertEquals(originalJson, place.getPhotosJson());
        assertEquals(PhotoSyncStatus.READY, place.getPhotosStatus());
        assertEquals(0, place.getPhotoFailureCount());
        verify(repository, never()).save(place);
    }

    @Test
    void reportFailure_duringRefreshKeepsRefreshingState() {
        place.setPhotosStatus(PhotoSyncStatus.REFRESHING);

        boolean accepted = service.reportFailure(7L, "https://img.test/broken.jpg");

        assertTrue(accepted);
        assertEquals(PhotoSyncStatus.REFRESHING, place.getPhotosStatus());
    }

    @Test
    void tryStartRefresh_returnsAtomicRepositoryClaimResult() {
        when(repository.claimPhotoRefresh(
                7L, PhotoSyncStatus.REFRESHING, PhotoSyncStatus.STALE, PhotoSyncStatus.FAILED))
                .thenReturn(1);

        assertTrue(service.tryStartRefresh(7L));
    }

    @Test
    void completeRefresh_replacesPhotosAndResetsFailureMetadata() {
        place.setPhotoFailureCount(3);
        place.setPhotosStatus(PhotoSyncStatus.REFRESHING);

        service.completeRefresh(7L,
                "[{\"url\":\"https://img.test/fresh.jpg\",\"thumbnail\":\"https://img.test/fresh-thumb.jpg\"}]");

        assertTrue(place.getPhotosJson().contains("fresh.jpg"));
        assertEquals(PhotoSyncStatus.READY, place.getPhotosStatus());
        assertEquals(0, place.getPhotoFailureCount());
        assertNotNull(place.getPhotosSyncedAt());
    }

    @Test
    void failRefresh_keepsNoPhotosAndMarksFailed() {
        place.setPhotosJson(null);
        place.setPhotosStatus(PhotoSyncStatus.REFRESHING);

        service.failRefresh(7L);

        assertNull(place.getPhotosJson());
        assertEquals(PhotoSyncStatus.FAILED, place.getPhotosStatus());
        assertEquals(1, place.getPhotoFailureCount());
    }
}
