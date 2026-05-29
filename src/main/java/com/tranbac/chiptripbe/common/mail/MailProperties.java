package com.tranbac.chiptripbe.common.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.mail")
@Getter
@Setter
public class MailProperties {
    private String from;
    private String verificationUrl;
    private String resetPasswordUrl;
    private int verificationExpiryHours = 24;
    private int resetPasswordExpiryHours = 1;
}
