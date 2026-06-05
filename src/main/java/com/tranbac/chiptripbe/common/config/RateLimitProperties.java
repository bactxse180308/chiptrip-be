package com.tranbac.chiptripbe.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rate-limit")
@Getter
@Setter
public class RateLimitProperties {
    private int loginLimit = 5;
    private int loginWindowMinutes = 15;
    private int registerLimit = 3;
    private int registerWindowMinutes = 60;
    private int forgotPasswordLimit = 3;
    private int forgotPasswordWindowMinutes = 60;
    private int generateLimit = 5;
    private int generateWindowMinutes = 1;
    private int defaultLimit = 60;
    private int defaultWindowMinutes = 1;
}
