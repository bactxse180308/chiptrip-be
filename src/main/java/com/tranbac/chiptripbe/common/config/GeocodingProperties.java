package com.tranbac.chiptripbe.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.geocoding")
@Getter
@Setter
public class GeocodingProperties {

    private String provider = "google";
    private GoogleMapsConfig googleMaps = new GoogleMapsConfig();

    @Getter
    @Setter
    public static class GoogleMapsConfig {
        private String apiKey;
        private String baseUrl = "https://maps.googleapis.com/maps/api";
    }
}