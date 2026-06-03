package com.tranbac.chiptripbe.common.security;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class GoogleTokenVerifier {

    @Value("${app.google.client-id:}")
    private String expectedClientId;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record GoogleUserInfo(String sub, String email, String name, String picture) {}

    public GoogleUserInfo verify(String idToken) throws Exception {
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("ID token is required");
        }

        String url = "https://oauth2.googleapis.com/tokeninfo?id_token=" + idToken;
        Request request = new Request.Builder().url(url).get().build();

        try (var response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.warn("Google token verification failed: {} - {}", response.code(), body);
                throw new SecurityException("Invalid Google ID token");
            }

            JsonNode root = objectMapper.readTree(body);

            String aud = root.has("aud") ? root.get("aud").asText() : "";
            if (aud.isEmpty() || !aud.equals(expectedClientId)) {
                log.warn("Google token audience mismatch: expected={}, got={}", expectedClientId, aud);
                throw new SecurityException("Token audience mismatch");
            }

            String email = root.has("email") ? root.get("email").asText() : "";
            String name = root.has("name") ? root.get("name").asText() : "";
            String picture = root.has("picture") ? root.get("picture").asText() : "";
            String sub = root.has("sub") ? root.get("sub").asText() : "";

            return new GoogleUserInfo(sub, email, name, picture);
        }
    }
}
