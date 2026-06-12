package com.tranbac.chiptripbe.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình gọi PostHog Query API (HogQL) phía server.
 * Personal key giữ ở backend — không lộ ra frontend.
 */
@Component
@ConfigurationProperties(prefix = "app.posthog")
@Getter
@Setter
public class PosthogProperties {
    private String apiHost = "https://us.posthog.com";
    private String projectId;
    private String personalKey;
    private int timeoutSeconds = 10;

    public boolean isConfigured() {
        return projectId != null && !projectId.isBlank()
                && personalKey != null && !personalKey.isBlank();
    }
}
