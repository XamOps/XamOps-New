package com.xammer.cloud.service.automation.workload.usage.store;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The capture executor: one thread, a bounded queue, and {@link ThreadPoolExecutor.AbortPolicy}.
 *
 * <p>One thread keeps the rollup to at most one connection of the small Hikari pool. When the queue
 * is full the snapshot is dropped ({@link UsageSampleCapture} counts it); it must never be
 * {@code CallerRunsPolicy}, which would run the capture on the agent's ingest thread. Pending work is
 * not waited for at shutdown: a lost five-minute sample is cheaper than a slow deploy.
 */
@Configuration
public class UsageRollupConfig {

    public static final String EXECUTOR_BEAN = "usageRollupExecutor";

    @Bean(name = EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor usageRollupExecutor(UsageRollupProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("UsageRollup-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        // Spring initializes the pool (afterPropertiesSet); calling initialize() here would leak one.
        return executor;
    }
}
