package com.xammer.cloud.config;

import com.xammer.cloud.config.multitenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Captures the current tenant ID AND MDC context map before a task is submitted
     * to any async executor, and restores both inside the worker thread.
     * MDC propagation ensures every log line from async threads carries [tenant=customerX].
     */
    @Bean
    public TaskDecorator tenantTaskDecorator() {
        return runnable -> {
            String tenant = TenantContext.getCurrentTenant();
            Map<String, String> mdcContext = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if (tenant != null) {
                        TenantContext.setCurrentTenant(tenant);
                    }
                    if (mdcContext != null) {
                        MDC.setContextMap(mdcContext);
                    } else if (tenant != null) {
                        MDC.put("tenant", tenant);
                    }
                    log.debug("▶ Async task starting — tenant={} thread={}", tenant, Thread.currentThread().getName());
                    runnable.run();
                } finally {
                    TenantContext.clear();
                    MDC.clear();
                }
            };
        };
    }

    @Bean(name = "prowlerTaskExecutor")
    public Executor prowlerTaskExecutor(TaskDecorator tenantTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // Queue must hold all accounts for the Saturday batch (target: 500+).
        // Actual concurrency is capped by SCAN_SEMAPHORE(2) in ProwlerService,
        // not by thread count — threads just block on the semaphore.
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("ProwlerAsync-");
        executor.setTaskDecorator(tenantTaskDecorator);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("prowlerTaskExecutor created: core={} max={} queue={}", 2, 4, 1000);
        return executor;
    }

    /**
     * Executor for the onboarding WAR report.
     *
     * <p>
     * Its tasks block for the length of a full scan — minutes, not milliseconds — so they
     * get their own small pool. On the shared default executor a batch of simultaneous
     * onboardings would occupy every thread and stall unrelated {@code @Async} work.
     *
     * <p>
     * Deliberately narrow: two concurrent scans is already a heavy load of cloud API
     * calls, and the queue is long because a delayed security email is fine while a
     * dropped one is not. {@code CallerRunsPolicy} is the last-resort backstop.
     */
    @Bean(name = "warReportExecutor")
    public Executor warReportExecutor(TaskDecorator tenantTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("WarReport-");
        executor.setTaskDecorator(tenantTaskDecorator);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("warReportExecutor created: core={} max={} queue={}", 2, 3, 500);
        return executor;
    }

    /**
     * Default executor for any bare @Async (no named executor) calls.
     * Provides the same TaskDecorator-based tenant context propagation as the named executors,
     * ensuring future @Async methods that don't specify an executor still propagate tenant context.
     */
    @Override
    @Bean
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("DefaultAsync-");
        executor.setTaskDecorator(tenantTaskDecorator()); // @Configuration CGLIB proxy returns singleton
        executor.initialize();
        log.info("DefaultAsync executor created: core={} max={} queue={}", 4, 10, 100);
        return executor;
    }
}
