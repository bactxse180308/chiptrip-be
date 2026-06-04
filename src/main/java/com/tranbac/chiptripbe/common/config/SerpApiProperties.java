package com.tranbac.chiptripbe.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.serpapi")
@Getter
@Setter
public class SerpApiProperties {
    private String apiKey;
    private String baseUrl = "https://serpapi.com";
    private int timeoutSeconds = 10;
    /** Tắt SerpApi khi muốn tiết kiệm quota, vẫn trả lat/lng từ Goong */
    private boolean enabled = true;
    /** Số ngày trước khi cache bị coi là hết hạn */
    private int cacheTtlDays = 7;
}