package com.tranbac.chiptripbe.module.place.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.module.geocoding.client.GoongClient;
import com.tranbac.chiptripbe.module.geocoding.client.MapsScraperClient;
import com.tranbac.chiptripbe.module.geocoding.client.ProviderLookupStatus;
import com.tranbac.chiptripbe.module.geocoding.client.SerpApiClient;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceCacheService;
import com.tranbac.chiptripbe.module.place.service.PlaceEnrichmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fix "scraper background-only": foreground generate (allowScraper=false) KHÔNG được chờ scraper
 * khi SerpApi miss — địa điểm để scraper enrich ở luồng nền, tránh vỡ deadline 55s. Mặc định
 * (allowScraper=true, dùng cho luồng nền + suggest-alternative) vẫn rơi về scraper như cũ.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaceEnrichmentServiceImplScraperTest {

    @Mock private PlaceCacheService placeCacheService;
    @Mock private PlaceCacheRepository placeCacheRepository;
    @Mock private GoongClient goongClient;
    @Mock private SerpApiClient serpApiClient;
    @Mock private MapsScraperClient mapsScraperClient;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private PlaceEnrichmentServiceImpl service;

    @BeforeEach
    void setup() throws Exception {
        // Cache miss → buộc gọi provider ngoài.
        when(placeCacheService.findFreshCache(any(), any())).thenReturn(Optional.empty());
        when(placeCacheService.isFreshNotFound(any(), any())).thenReturn(false);
        // Goong geocode OK, address chứa destination → khớp vùng, KHÔNG rơi về SerpApi fallback.
        when(goongClient.forwardGeocodeDetailed(any())).thenReturn(new GoongClient.GeocodeLookup(
                Optional.of(new GoongClient.GeocodeResult(
                null, "123 Phố ABC, Hà Nội, Việt Nam",
                new BigDecimal("21.000000"), new BigDecimal("105.800000"), "Hà Nội", null)),
                ProviderLookupStatus.FOUND));
        when(placeCacheRepository.findFirstByNormalizedNameAndNormalizedDestination(any(), any()))
                .thenReturn(Optional.empty());
        // SerpApi miss (không candidate) → nhánh quyết định có rơi về scraper hay không.
        when(serpApiClient.searchPlaceCandidatesDetailed(any())).thenReturn(
                new SerpApiClient.PlaceCandidateSearchResult(List.of(), ProviderLookupStatus.NOT_FOUND));
        when(objectMapper.writeValueAsString(any())).thenReturn("[{}]");
        when(placeCacheService.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mapsScraperClient.scrape(any(), anyBoolean())).thenReturn(Optional.empty());
    }

    @Test
    void resolvePlace_allowScraperFalse_serpMiss_doesNotCallScraper() {
        Optional<PlaceCache> result = service.resolvePlace(
                "Bún chả Hương Liên Hà Nội", "Hà Nội", List.of(), null, false,
                PlaceEnrichmentService.EnrichmentDepth.FULL, false, false);

        assertTrue(result.isPresent());
        assertFalse(result.get().isSerpEnriched(), "SerpApi miss + không scraper → serpEnriched=false (để enrich nền)");
        verify(mapsScraperClient, never()).scrape(any(), anyBoolean());
    }

    @Test
    void resolvePlace_negativeCacheHit_skipsProviders() {
        when(placeCacheService.isFreshNotFound(any(), any())).thenReturn(true);

        Optional<PlaceCache> result = service.resolvePlace(
                "Dia diem khong ton tai", "Ha Noi", List.of(), null, false,
                PlaceEnrichmentService.EnrichmentDepth.FULL, false, false);

        assertTrue(result.isEmpty());
        verify(goongClient, never()).forwardGeocodeDetailed(any());
        verify(serpApiClient, never()).searchPlaceCandidatesDetailed(any());
        verify(mapsScraperClient, never()).scrape(any(), anyBoolean());
    }

    @Test
    void resolvePlace_defaultAllowsScraper_serpMiss_fallsBackToScraper() {
        // Overload 7 tham số (không allowScraper) mặc định allowScraper=true — luồng nền / suggest-alternative.
        service.resolvePlace(
                "Bún chả Hương Liên Hà Nội", "Hà Nội", List.of(), null, false,
                PlaceEnrichmentService.EnrichmentDepth.FULL, false);

        verify(mapsScraperClient).scrape(any(), eq(false));
    }

    @Test
    void resolvePlace_allowScraperFalse_preferSerpMiss_doesNotCallScraper() {
        Optional<PlaceCache> result = service.resolvePlace(
                "Bun cha Huong Lien Ha Noi", "Ha Noi", List.of(), null, true,
                PlaceEnrichmentService.EnrichmentDepth.FULL, false, false);

        assertTrue(result.isPresent());
        assertFalse(result.get().isSerpEnriched());
        verify(mapsScraperClient, never()).scrape(any(), anyBoolean());
    }

    @Test
    void resolvePlace_allowScraperFalse_serpCandidateMatch_enrichesWithoutScraper() {
        when(serpApiClient.searchPlaceCandidatesDetailed(anyString()))
                .thenReturn(new SerpApiClient.PlaceCandidateSearchResult(
                        List.of(serpCandidate()), ProviderLookupStatus.FOUND));
        when(serpApiClient.fetchPhotos(anyString()))
                .thenReturn(List.of(Map.of("url", "https://example.test/photo.jpg", "thumbnail", "https://example.test/thumb.jpg")));
        when(serpApiClient.fetchReviews(anyString()))
                .thenReturn(List.of(Map.of("text", "Tot")));

        Optional<PlaceCache> result = service.resolvePlace(
                "Bun cha Huong Lien Ha Noi", "Ha Noi", List.of(), null, true,
                PlaceEnrichmentService.EnrichmentDepth.FULL, false, false);

        assertTrue(result.isPresent());
        assertTrue(result.get().isSerpEnriched());
        verify(mapsScraperClient, never()).scrape(any(), anyBoolean());
    }

    @Test
    void resolvePlace_allowScraperTrue_preferSerpMiss_fallsBackToScraper() {
        service.resolvePlace(
                "Bun cha Huong Lien Ha Noi", "Ha Noi", List.of(), null, true,
                PlaceEnrichmentService.EnrichmentDepth.FULL, false, true);

        verify(mapsScraperClient).scrape(any(), eq(false));
    }

    @Test
    void resolvePlace_forceRefreshAllowScraperFalse_serpMiss_doesNotCallScraper() {
        service.resolvePlace(
                "Bun cha Huong Lien Ha Noi", "Ha Noi", List.of(), null, false,
                PlaceEnrichmentService.EnrichmentDepth.FULL, true, false);

        verify(placeCacheService, never()).findFreshCache(any(), any());
        verify(mapsScraperClient, never()).scrape(any(), anyBoolean());
    }

    private SerpApiClient.PlaceData serpCandidate() {
        return new SerpApiClient.PlaceData(
                "data-1",
                "place-1",
                "Bun cha Huong Lien",
                "Restaurant",
                "24 Le Van Huu, Ha Noi, Viet Nam",
                new BigDecimal("4.6"),
                1200,
                "Open",
                "Open 8 AM to 9 PM",
                "{\"Monday\":\"8:00-21:00\"}",
                true,
                "https://example.test/thumb.jpg",
                "0123456789",
                "https://example.test",
                new BigDecimal("21.018000"),
                new BigDecimal("105.853000"));
    }
}
