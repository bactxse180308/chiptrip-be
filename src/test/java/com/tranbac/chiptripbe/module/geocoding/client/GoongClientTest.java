package com.tranbac.chiptripbe.module.geocoding.client;

import com.tranbac.chiptripbe.common.config.GoongProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test parseGeocodeResult: đảm bảo fail-soft khi V2 không có compound,
 * và parse đúng province/commune khi V2 trả về compound.
 */
class GoongClientTest {

    private final GoongClient client = new GoongClient(WebClient.builder(), new GoongProperties());

    @Test
    void parseGeocodeResult_withCompound_returnsProvinceAndCommune() {
        Map<String, Object> result = Map.of(
                "place_id", "abc123",
                "formatted_address", "Phố Cổ Hội An, Hội An, Đà Nẵng",
                "geometry", Map.of("location", Map.of("lat", 15.879670, "lng", 108.336770)),
                "compound", Map.of("province", "Đà Nẵng", "commune", "Hội An")
        );

        Optional<GoongClient.GeocodeResult> parsed = client.parseGeocodeResult(result);

        assertTrue(parsed.isPresent());
        GoongClient.GeocodeResult g = parsed.get();
        assertEquals("abc123", g.placeId());
        assertEquals("Phố Cổ Hội An, Hội An, Đà Nẵng", g.formattedAddress());
        assertEquals(0, BigDecimal.valueOf(15.879670).compareTo(g.lat()));
        assertEquals(0, BigDecimal.valueOf(108.336770).compareTo(g.lng()));
        assertEquals("Đà Nẵng", g.provinceName());
        assertEquals("Hội An", g.communeName());
    }

    @Test
    void parseGeocodeResult_withoutCompound_returnsLatLngWithNullAdmin() {
        // V1 response shape (no compound block) — fail-soft phải vẫn có lat/lng
        Map<String, Object> result = Map.of(
                "place_id", "xyz789",
                "formatted_address", "Hồ Hoàn Kiếm, Hà Nội",
                "geometry", Map.of("location", Map.of("lat", 21.028511, "lng", 105.852152))
        );

        Optional<GoongClient.GeocodeResult> parsed = client.parseGeocodeResult(result);

        assertTrue(parsed.isPresent());
        GoongClient.GeocodeResult g = parsed.get();
        assertNotNull(g.lat());
        assertNotNull(g.lng());
        assertNull(g.provinceName());
        assertNull(g.communeName());
    }

    @Test
    void parseGeocodeResult_missingLatLng_returnsEmpty() {
        Map<String, Object> result = Map.of(
                "place_id", "no-geo",
                "formatted_address", "Somewhere",
                "geometry", Map.of("location", Map.of()) // empty location
        );

        Optional<GoongClient.GeocodeResult> parsed = client.parseGeocodeResult(result);

        assertTrue(parsed.isEmpty());
    }

    @Test
    void parseGeocodeResult_partialCompound_returnsAvailableField() {
        // compound chỉ có province, thiếu commune
        Map<String, Object> result = Map.of(
                "place_id", "p1",
                "formatted_address", "Đà Lạt, Lâm Đồng",
                "geometry", Map.of("location", Map.of("lat", 11.94, "lng", 108.45)),
                "compound", Map.of("province", "Lâm Đồng")
        );

        Optional<GoongClient.GeocodeResult> parsed = client.parseGeocodeResult(result);

        assertTrue(parsed.isPresent());
        assertEquals("Lâm Đồng", parsed.get().provinceName());
        assertNull(parsed.get().communeName());
    }

    @Test
    void parseGeocodeResult_blankCompoundString_treatedAsNull() {
        Map<String, Object> result = Map.of(
                "place_id", "p2",
                "formatted_address", "X",
                "geometry", Map.of("location", Map.of("lat", 10.0, "lng", 106.0)),
                "compound", Map.of("province", "", "commune", "   ")
        );

        Optional<GoongClient.GeocodeResult> parsed = client.parseGeocodeResult(result);

        assertTrue(parsed.isPresent());
        assertNull(parsed.get().provinceName());
        assertNull(parsed.get().communeName());
    }

    @Test
    void parseGeocodeResult_compoundIsNotMap_doesNotCrash() {
        Map<String, Object> result = Map.of(
                "place_id", "p3",
                "formatted_address", "X",
                "geometry", Map.of("location", Map.of("lat", 10.0, "lng", 106.0)),
                "compound", List.of("unexpected") // sai shape, không phải Map
        );

        Optional<GoongClient.GeocodeResult> parsed = client.parseGeocodeResult(result);

        assertTrue(parsed.isPresent());
        assertNull(parsed.get().provinceName());
        assertNull(parsed.get().communeName());
    }
}
