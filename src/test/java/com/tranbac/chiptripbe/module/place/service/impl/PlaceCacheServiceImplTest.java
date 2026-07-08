package com.tranbac.chiptripbe.module.place.service.impl;

import com.tranbac.chiptripbe.common.config.SerpApiProperties;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaceCacheServiceImplTest {

    @Test
    void isFreshNotFound_withinTtl_returnsTrue() {
        PlaceCacheRepository repository = mock(PlaceCacheRepository.class);
        SerpApiProperties properties = new SerpApiProperties();
        properties.setNegativeCacheTtlHours(6);
        PlaceCacheServiceImpl service = new PlaceCacheServiceImpl(repository, properties);
        PlaceCache place = PlaceCache.builder()
                .name("Missing")
                .normalizedName("missing")
                .normalizedDestination("ha noi")
                .notFound(true)
                .notFoundAt(LocalDateTime.now().minusHours(1))
                .build();
        when(repository.findFirstByNormalizedNameAndNormalizedDestination("missing", "ha noi"))
                .thenReturn(Optional.of(place));

        assertTrue(service.isFreshNotFound("missing", "ha noi"));
    }

    @Test
    void save_positivePlaceClearsNegativeCacheFlags() {
        PlaceCacheRepository repository = mock(PlaceCacheRepository.class);
        SerpApiProperties properties = new SerpApiProperties();
        PlaceCacheServiceImpl service = new PlaceCacheServiceImpl(repository, properties);
        PlaceCache place = PlaceCache.builder()
                .name("Found")
                .normalizedName("found")
                .notFound(true)
                .notFoundAt(LocalDateTime.now())
                .notFoundReason("old miss")
                .build();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PlaceCache saved = service.save(place);

        assertFalse(saved.isNotFound());
        assertNull(saved.getNotFoundAt());
        assertNull(saved.getNotFoundReason());
    }
}
