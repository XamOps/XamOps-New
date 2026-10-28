package com.xammer.cloud.config;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.service.cur.CostSourceContext;
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
     * Captures the current tenant ID, MDC context map AND cost-source preference before a
     * task is submitted to any async executor, and restores all three inside the worker
     * thread. MDC propagation ensures every log line from async threads carries
     * [tenant=customerX].
     *
     * <p>The cost-source preference rides along for the same reason as tenant: almost all
     * cost work runs on the awsTaskExecutor pool, so a preference set on the request
     * thread would otherwise be lost exactly where it needs to be read.
     */
    @Bean
    public TaskDecorator tenantTaskDecorator() {
        return runnable -> {
            String tenant = TenantContext.getCurrentTenant();
            Map<String, String> mdcContext = MDC.getCopyOfContextMap();
            CostSourceContext costSource = CostSourceContext.peek();
            return () -> {
                try {
                    if (tenant != null) {
                        TenantContext.setCurrentTenant(tenant);
                    }
                    if (costSource != null) {
                        CostSourceContext.set(costSource);
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
                    CostSourceContext.clear();
                    MDC.clear();
                }
            };
        };
    }

    /**
     * Fleet-wide ceiling of concurrent sequenced stack-update jobs per service instance.
     *
     * <p>Each {@code StackSequenceService.runSequence} is a long-blocking task: the connection
     * poll alone is bounded at ~18 min, then the CUR retries (~72 s) plus the CUR settle-watch
     * (~15 min) mean one job can hold its worker thread for ~33 min. This is an operator action
     * run a handful at a time, not a bulk workflow, so 8 simultaneous long-running sequences per
     * instance comfortably covers expected peak while bounding thread/connection use. The
     * single-job-per-account guard already caps each account at one in-flight sequence, so hitting
     * this ceiling requires 8 distinct accounts updating at once; the 9th gets a deterministic
     * SEQUENCE_CAPACITY 409 rather than a degraded/blocked request. This is the one number to tune.
     */
    private static final int MAX_CONCURRENT_SEQUENCES = 8;

    /**
     * Executor for the sequenced connection→CUR stack-update job.
     *
     * <p>Core == max == {@link #MAX_CONCURRENT_SEQUENCES} with a SynchronousQueue (queue
     * capacity 0): a submission either gets a free thread immediately or is rejected — a job is
     * never silently queued behind a running ~33-minute job. The rejection handler is
     * {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy} — <strong>never</strong>
     * CallerRunsPolicy — because a long-blocking task run on the HTTP request thread would block
     * the 202 for the whole sequence. On rejection {@code start} catches the
     * {@link java.util.concurrent.RejectedExecutionException} and returns a 409 SEQUENCE_CAPACITY.
     *
     * <p>{@code tenantTaskDecorator} is required, not optional: the worker calls
     * {@code CurStackService.applyAsync → persistIdentity → cloudAccountRepository.save}, a
     * tenant-routed DB write that would otherwise route to the fallback system DB.
     */
    @Bean(name = "stackSequenceExecutor")
    public Executor stackSequenceExecutor(TaskDecorator tenantTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(MAX_CONCURRENT_SEQUENCES);
        executor.setMaxPoolSize(MAX_CONCURRENT_SEQUENCES);
        executor.setQueueCapacity(0); // SynchronousQueue — never hold a job waiting behind a long-blocking job
        executor.setThreadNamePrefix("StackSeq-");
        executor.setTaskDecorator(tenantTaskDecorator);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        log.info("stackSequenceExecutor created: core={} max={} queue={}",
                MAX_CONCURRENT_SEQUENCES, MAX_CONCURRENT_SEQUENCES, 0);
        return executor;
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
