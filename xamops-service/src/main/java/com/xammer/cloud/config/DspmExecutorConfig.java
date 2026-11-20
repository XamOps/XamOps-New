package com.xammer.cloud.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executor for the XamLens fan-outs over the Matters per-datastore routes (build 5: the Misconfigurations
 * page reads {@code issues/overview} once per datastore).
 *
 * <p>The request thread always works alongside the helpers, so a full pool only means fewer helpers.
 * The rejection handler is therefore {@link ThreadPoolExecutor.AbortPolicy}, <strong>never</strong>
 * CallerRunsPolicy: {@code tenantTaskDecorator} clears TenantContext and MDC in {@code finally}, which on
 * the request thread would wipe the request's own context.
 */
@Configuration
public class DspmExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(DspmExecutorConfig.class);

    private static final int POOL_SIZE = 8;
    private static final int QUEUE_CAPACITY = 64;

    @Bean(name = "dspmFanOutExecutor")
    public Executor dspmFanOutExecutor(TaskDecorator tenantTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("dspm-fanout-");
        executor.setTaskDecorator(tenantTaskDecorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        log.info("dspmFanOutExecutor created: core={} max={} queue={}", POOL_SIZE, POOL_SIZE, QUEUE_CAPACITY);
        return executor;
    }
}
