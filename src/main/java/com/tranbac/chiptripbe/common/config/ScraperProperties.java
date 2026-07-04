package com.tranbac.chiptripbe.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình cho sidecar scrape Google Maps (fallback khi SerpApi hết quota).
 * Sidecar là process Python (FastAPI + Playwright) chạy localhost trên cùng VPS.
 */
@Component
@ConfigurationProperties(prefix = "app.scraper")
@Getter
@Setter
public class ScraperProperties {
    /** Tắt hẳn scraper (fallback về hành vi Goong+SerpApi cũ) khi sidecar hỏng/không deploy. */
    private boolean enabled = false;
    /** URL sidecar — chỉ nghe localhost, không expose ra ngoài. */
    private String baseUrl = "http://127.0.0.1:8899";
    /** Trần thời gian chờ 1 request scrape. Phải >= NAV_TIMEOUT_S của sidecar (mặc định 50s). */
    private int timeoutSeconds = 55;
    private int maxReviews = 3;
    private int maxPhotos = 5;
}
