package com.tranbac.chiptripbe.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("enrichmentExecutor")
    public Executor enrichmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Sized theo rate gate của SerpApi (app.serpapi.requests-per-second): fan-out rộng hơn chỉ
        // khiến các thread thừa dồn vào token bucket rồi tự drop sau timeout → mất enrichment vô ích.
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("enrich-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // Queue đầy → chạy đồng bộ trên request thread thay vì ném RejectedExecutionException
        // (AbortPolicy mặc định sẽ đánh sập cả generateTrip dù từng task đã fail-soft)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Pool riêng cho worker sinh lịch trình bất đồng bộ (generate-async). Mỗi job giữ 1 thread
     * trong suốt ~30-90s và bản thân nó còn submit tiếp vào enrichmentExecutor để geocode —
     * nên PHẢI tách pool, không dùng chung enrichmentExecutor (tránh self-starve/deadlock).
     */
    @Bean("tripGenerateExecutor")
    public Executor tripGenerateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("trip-gen-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
