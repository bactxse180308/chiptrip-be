package com.tranbac.chiptripbe.module.ai.service;

import com.tranbac.chiptripbe.common.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Quản lý nhiều API key Gemini và tự xoay vòng khi một key hết hạn mức (429) hoặc bị từ chối (401/403).
 *
 * <p>Giữ con trỏ "key đang dùng" dạng sticky round-robin: các request dùng lại key vừa hoạt động,
 * chỉ nhảy sang key kế tiếp khi key hiện tại bị từ chối. Con trỏ dùng chung giữa mọi request nên một
 * khi key bị xác định là hết, các request sau bỏ qua luôn key đó. Thread-safe.
 */
@Slf4j
@Component
public class AiKeyPool {

    private final List<String> keys;
    private final AtomicInteger cursor = new AtomicInteger(0);

    public AiKeyPool(AiProperties aiProperties) {
        this.keys = aiProperties.getOpenaiCompat().getApiKeys().stream()
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .distinct()
                .toList();
        if (keys.isEmpty()) {
            throw new IllegalStateException(
                    "Chưa cấu hình API key AI. Đặt AI_API_KEYS (nhiều key, ngăn bởi dấu phẩy) hoặc AI_API_KEY.");
        }
        log.info("AiKeyPool: nạp {} API key Gemini", keys.size());
    }

    /** Số lượng key trong pool. */
    public int size() {
        return keys.size();
    }

    /** Key đang dùng hiện tại (sticky). */
    public String current() {
        return keys.get(Math.floorMod(cursor.get(), keys.size()));
    }

    /**
     * Xoay sang key kế tiếp sau khi {@code rejectedKey} bị từ chối (429/401/403).
     * Chỉ xoay nếu key bị từ chối vẫn đang là key hiện tại — tránh nhảy nhiều bước khi
     * nhiều request song song cùng báo lỗi trên cùng một key.
     */
    public synchronized void rotate(String rejectedKey) {
        if (keys.size() == 1) {
            return;
        }
        if (rejectedKey != null && rejectedKey.equals(current())) {
            int next = Math.floorMod(cursor.incrementAndGet(), keys.size());
            log.warn("Đổi sang API key Gemini #{}/{}", next + 1, keys.size());
        }
    }
}
