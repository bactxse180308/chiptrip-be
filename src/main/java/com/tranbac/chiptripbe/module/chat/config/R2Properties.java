package com.tranbac.chiptripbe.module.chat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config cho Cloudflare R2 (S3-compatible).
 * - endpoint: dạng https://&lt;accountid&gt;.r2.cloudflarestorage.com (KHÔNG kèm bucket).
 * - publicUrl: URL public dạng https://pub-xxx.r2.dev hoặc custom domain để render ảnh ở FE.
 */
@Component
@ConfigurationProperties(prefix = "app.r2")
@Getter
@Setter
public class R2Properties {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketName;
    private String publicUrl;
}
