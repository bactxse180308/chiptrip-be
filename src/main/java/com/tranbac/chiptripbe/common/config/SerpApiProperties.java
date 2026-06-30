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
    /** Một hoặc nhiều API key, ngăn cách bằng dấu phẩy (cơ chế xoay vòng). VD: key1,key2,key3 */
    private String apiKey;
    private String baseUrl = "https://serpapi.com";
    private int timeoutSeconds = 10;
    /** Tắt SerpApi khi muốn tiết kiệm quota, vẫn trả lat/lng từ Goong */
    private boolean enabled = true;
    /** Số ngày trước khi cache bị coi là hết hạn */
    private int cacheTtlDays = 7;
    /** Số phút backoff giữa các lần retry SerpApi khi row chưa enrich đủ — chống thundering herd khi SerpApi down */
    private int retryBackoffMinutes = 60;
    /** Số request SerpApi đồng thời tối đa (throttle chống 429 khi enrichment fan-out song song) */
    private int maxConcurrent = 3;
    /** Số giờ bỏ qua một key sau khi phát hiện hết lượt (HTTP 401), trước khi thử lại — quota free reset theo tháng */
    private int exhaustedCooldownHours = 6;
}