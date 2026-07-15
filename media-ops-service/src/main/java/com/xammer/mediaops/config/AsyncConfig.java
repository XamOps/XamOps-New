package com.xammer.mediaops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    // FFmpeg saturates every CPU core it gets, so parallel transcodes make all of
    // them slower than running the same jobs back to back. Jobs beyond the limit
    // stay QUEUED (their status since creation) until a worker slot frees up.
    // Note: a PAUSED job keeps holding its slot — stop it instead if the queue
    // needs to move.
    @Value("${mediaops.transcode.max-concurrent:1}")
    private int maxConcurrentTranscodes;

    @Bean(name = "transcodeExecutor")
    public ThreadPoolTaskExecutor transcodeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxConcurrentTranscodes);
        executor.setMaxPoolSize(maxConcurrentTranscodes);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("transcode-");
        executor.initialize();
        return executor;
    }
}
