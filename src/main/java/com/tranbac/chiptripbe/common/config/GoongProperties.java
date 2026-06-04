package com.tranbac.chiptripbe.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.goong")
@Getter
@Setter
public class GoongProperties {
    private String apiKey;
    private String baseUrl = "https://rsapi.goong.io";
    private int timeoutSeconds = 5;
}