package com.tranbac.chiptripbe.module.geocoding.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.config.ProviderResilienceProperties;
import com.tranbac.chiptripbe.common.config.SerpApiProperties;
import com.tranbac.chiptripbe.common.resilience.ProviderResilience;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SerpApiClientTest {

    @Test
    void quotaExhaustedKeyStaysExcludedForCurrentProcess() {
        SerpApiProperties properties = new SerpApiProperties();
        properties.setApiKey("exhausted-key,healthy-key");
        SerpApiKeyPool keyPool = new SerpApiKeyPool(properties);

        keyPool.markQuotaExhausted("exhausted-key");

        assertEquals(List.of("healthy-key"), keyPool.availableKeys());
    }

    @Test
    void quotaExhaustedKeyRotatesToNextKeyWithoutGlobalCooldown() {
        AtomicInteger calls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            String key = UriComponentsBuilder.fromUri(request.url()).build()
                    .getQueryParams().getFirst("api_key");
            if ("exhausted-key".equals(key)) {
                return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .header("Retry-After", "60")
                        .body("{\"error\":\"Your account has run out of searches.\"}")
                        .build());
            }
            return Mono.just(jsonResponse(HttpStatus.OK, """
                    {"local_results":[{"title":"Healthy result","data_id":"0x1:0x2",
                    "gps_coordinates":{"latitude":10.0,"longitude":106.0}}]}
                    """));
        });
        SerpApiProperties properties = new SerpApiProperties();
        properties.setApiKey("exhausted-key,healthy-key");
        properties.setRequestsPerSecond(100);
        properties.setMaxConcurrent(100);
        SerpApiClient client = new SerpApiClient(
                builder,
                properties,
                new ObjectMapper(),
                new SerpApiKeyPool(properties),
                new ProviderResilience(new ProviderResilienceProperties()));
        client.init();

        SerpApiClient.PlaceCandidateSearchResult result =
                client.searchPlaceCandidatesDetailed("Ben Thanh Market");
        SerpApiClient.PlaceCandidateSearchResult secondResult =
                client.searchPlaceCandidatesDetailed("Ben Thanh Market");

        assertEquals(ProviderLookupStatus.FOUND, result.status());
        assertEquals("Healthy result", result.candidates().getFirst().title());
        assertEquals(ProviderLookupStatus.FOUND, secondResult.status());
        assertEquals(3, calls.get());
    }

    @Test
    void rateLimit429StillOpensGlobalCooldownWithoutRotatingKeys() {
        AtomicInteger calls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(jsonResponse(HttpStatus.TOO_MANY_REQUESTS,
                    "{\"error\":\"Hourly rate limit exceeded.\"}"));
        });
        SerpApiProperties properties = new SerpApiProperties();
        properties.setApiKey("first-key,second-key");
        properties.setRequestsPerSecond(100);
        properties.setMaxConcurrent(100);
        SerpApiClient client = new SerpApiClient(
                builder,
                properties,
                new ObjectMapper(),
                new SerpApiKeyPool(properties),
                new ProviderResilience(new ProviderResilienceProperties()));
        client.init();

        SerpApiClient.PlaceCandidateSearchResult firstResult =
                client.searchPlaceCandidatesDetailed("Ben Thanh Market");
        SerpApiClient.PlaceCandidateSearchResult secondResult =
                client.searchPlaceCandidatesDetailed("Ben Thanh Market");

        assertEquals(ProviderLookupStatus.TEMPORARY_UNAVAILABLE, firstResult.status());
        assertEquals(ProviderLookupStatus.TEMPORARY_UNAVAILABLE, secondResult.status());
        assertEquals(1, calls.get());
    }

    private ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
