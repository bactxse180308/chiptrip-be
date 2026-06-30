package com.tranbac.chiptripbe.module.geocoding.client;

import com.tranbac.chiptripbe.common.config.SerpApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool key SerpApi hỗ trợ xoay vòng: tự bỏ qua key đã hết lượt (HTTP 401 hoặc body
 * báo "out of searches") và thử lại sau cooldown.
 *
 * Key khai báo trong biến SERPAPI_API_KEY, nhiều key ngăn cách bằng dấu phẩy.
 * Trạng thái exhausted lưu in-memory (ConcurrentHashMap) → reset khi restart app.
 */
@Slf4j
@Component
public class SerpApiKeyPool {

    private static final long MS_PER_HOUR = 3_600_000L;

    private final List<String> keys;
    private final long cooldownMs;

    /** key → thời điểm (epoch ms) hết cooldown; còn trong map nghĩa là đang bị bỏ qua. */
    private final ConcurrentHashMap<String, Long> exhaustedUntil = new ConcurrentHashMap<>();
    /** Con trỏ round-robin để không dồn toàn bộ tải vào key đầu danh sách. */
    private final AtomicInteger cursor = new AtomicInteger(0);

    public SerpApiKeyPool(SerpApiProperties properties) {
        this.keys = parseKeys(properties.getApiKey());
        this.cooldownMs = Math.max(1, properties.getExhaustedCooldownHours()) * MS_PER_HOUR;
        if (keys.isEmpty()) {
            log.info("SerpApi: không có API key — enrichment qua SerpApi sẽ bị bỏ qua");
        } else {
            log.info("SerpApi: nạp {} API key cho cơ chế xoay vòng", keys.size());
        }
    }

    private static List<String> parseKeys(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String k : raw.split(",")) {
            String trimmed = k.trim();
            if (!trimmed.isBlank() && !result.contains(trimmed)) result.add(trimmed);
        }
        return List.copyOf(result);
    }

    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    /**
     * Danh sách key nên thử, theo thứ tự ưu tiên round-robin, đã loại các key còn trong cooldown.
     * Key hết cooldown được dọn khỏi map và cho dùng lại.
     */
    List<String> availableKeys() {
        int size = keys.size();
        if (size == 0) return List.of();
        long now = System.currentTimeMillis();
        int start = Math.floorMod(cursor.getAndIncrement(), size);
        List<String> ordered = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String key = keys.get((start + i) % size);
            Long until = exhaustedUntil.get(key);
            if (until != null) {
                if (now < until) continue;     // còn cooldown → bỏ qua
                exhaustedUntil.remove(key);    // hết cooldown → cho dùng lại
            }
            ordered.add(key);
        }
        return ordered;
    }

    /** Đánh dấu một key hết lượt / lỗi auth → bỏ qua trong khoảng cooldown đã cấu hình. */
    void markExhausted(String key) {
        exhaustedUntil.put(key, System.currentTimeMillis() + cooldownMs);
        log.warn("SerpApi: key ...{} bị đánh dấu hết lượt, bỏ qua {} giờ tới",
                tail(key), cooldownMs / MS_PER_HOUR);
    }

    /** Chỉ lộ 4 ký tự cuối để tránh log lộ API key. */
    private static String tail(String key) {
        return key.length() <= 4 ? "****" : key.substring(key.length() - 4);
    }
}
