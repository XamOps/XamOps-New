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
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ProwlerAsync-");
        executor.setTaskDecorator(tenantTaskDecorator);
        executor.initialize();
        log.info("prowlerTaskExecutor created: core={} max={} queue={}", 2, 5, 50);
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
