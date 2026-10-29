package com.xammer.cloud.config;

import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdcTaskDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The dedicated AutoSpotting v2 executors and schedulers (design-core.md §4.2, plan-phase-04.md task
 * 4.2). None of these beans uses the shared {@code taskScheduler} ({@code SchedulingConfig.java}) or
 * the DefaultAsync executor ({@code AsyncConfig.java}): a busy job can't delay a dispatcher claim, a
 * preemption or a revert, and heartbeats can't be starved.
 *
 * <table>
 *   <tr><th>Bean</th><th>Shape</th><th>Prefix</th></tr>
 *   <tr><td>{@code spotOpsExecutor}</td><td>4/4, SynchronousQueue, AbortPolicy</td><td>{@code spot-ops-}</td></tr>
 *   <tr><td>{@code spotDispatchScheduler}</td><td>1 thread</td><td>{@code spot-dispatch-}</td></tr>
 *   <tr><td>{@code spotTaskScheduler}</td><td>3 threads</td><td>{@code spot-sched-}</td></tr>
 *   <tr><td>{@code spotJobsExecutor}</td><td>3/3, queue 10</td><td>{@code spot-jobs-}</td></tr>
 *   <tr><td>{@code spotLeaseHeartbeat}</td><td>1 thread</td><td>{@code spot-lease-hb}</td></tr>
 * </table>
 *
 * <p>Every executor and scheduler gets the existing {@code tenantTaskDecorator}
 * ({@code AsyncConfig.java:35}) composed with {@link AutoSpottingMdcTaskDecorator}, so tenant /
 * cost-source context and the {@code operationId}/{@code groupId}/{@code requestId} MDC keys ride
 * onto every pool thread (AC-SPN-13, F-03). Sizes come from {@code autospotting.executor.*}.
 *
 * <p>The {@code spotJobsExecutor} queue is bounded (10) with {@code AbortPolicy}: a full queue
 * rejects the submit, and {@code JobRunner} turns that into
 * {@code autospotting.job.skipped{job,reason=busy}} without running anything on the caller's thread.
 * The {@code spotSqsListener} bean (design-core.md §4.2) arrives with the AWS SQS listener in a later
 * phase.
 */
@Configuration
public class AutoSpottingExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingExecutorConfig.class);

    private final int opsPoolSize;
    private final int jobsPoolSize;
    private final int jobsQueueCapacity;
    private final int taskSchedulerPoolSize;

    public AutoSpottingExecutorConfig(
            @Value("${autospotting.executor.ops-pool-size:4}") int opsPoolSize,
            @Value("${autospotting.executor.jobs-pool-size:3}") int jobsPoolSize,
            @Value("${autospotting.executor.jobs-queue-capacity:10}") int jobsQueueCapacity,
            @Value("${autospotting.executor.task-scheduler-pool-size:3}") int taskSchedulerPoolSize) {
        this.opsPoolSize = opsPoolSize;
        this.jobsPoolSize = jobsPoolSize;
        this.jobsQueueCapacity = jobsQueueCapacity;
        this.taskSchedulerPoolSize = taskSchedulerPoolSize;
    }

    /** Composes the existing tenant decorator with the AutoSpotting MDC decorator, in that order. */
    private TaskDecorator spotDecorator(TaskDecorator tenantTaskDecorator, AutoSpottingMdcTaskDecorator mdcDecorator) {
        return new CompositeTaskDecorator(List.of(tenantTaskDecorator, mdcDecorator));
    }

    /** Operation steps. core 4 / max 4, SynchronousQueue (queue 0), AbortPolicy (design §4.2). */
    @Bean
    public ThreadPoolTaskExecutor spotOpsExecutor(TaskDecorator tenantTaskDecorator,
                                                  AutoSpottingMdcTaskDecorator mdcDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(opsPoolSize);
        executor.setMaxPoolSize(opsPoolSize);
        executor.setQueueCapacity(0); // SynchronousQueue: no buffering, hand off or reject
        executor.setThreadNamePrefix("spot-ops-");
        executor.setTaskDecorator(spotDecorator(tenantTaskDecorator, mdcDecorator));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(25);
        executor.initialize();
        log.info("spotOpsExecutor created: core={} max={} queue=0 (SynchronousQueue)", opsPoolSize, opsPoolSize);
        return executor;
    }

    /** The 5 s dispatcher poll only. 1 thread, prefix {@code spot-dispatch-}. */
    @Bean
    public ThreadPoolTaskScheduler spotDispatchScheduler(TaskDecorator tenantTaskDecorator,
                                                         AutoSpottingMdcTaskDecorator mdcDecorator) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("spot-dispatch-");
        scheduler.setTaskDecorator(spotDecorator(tenantTaskDecorator, mdcDecorator));
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(25);
        scheduler.initialize();
        return scheduler;
    }

    /** Triggers for every other AutoSpotting {@code @Scheduled} job. 3 threads, {@code spot-sched-}. */
    @Bean
    public ThreadPoolTaskScheduler spotTaskScheduler(TaskDecorator tenantTaskDecorator,
                                                     AutoSpottingMdcTaskDecorator mdcDecorator) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(taskSchedulerPoolSize);
        scheduler.setThreadNamePrefix("spot-sched-");
        scheduler.setTaskDecorator(spotDecorator(tenantTaskDecorator, mdcDecorator));
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(25);
        scheduler.initialize();
        return scheduler;
    }

    /** Job bodies. core 3 / max 3, queue 10, AbortPolicy; a full queue is counted busy by JobRunner. */
    @Bean
    public ThreadPoolTaskExecutor spotJobsExecutor(TaskDecorator tenantTaskDecorator,
                                                   AutoSpottingMdcTaskDecorator mdcDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(jobsPoolSize);
        executor.setMaxPoolSize(jobsPoolSize);
        executor.setQueueCapacity(jobsQueueCapacity);
        executor.setThreadNamePrefix("spot-jobs-");
        executor.setTaskDecorator(spotDecorator(tenantTaskDecorator, mdcDecorator));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(25);
        executor.initialize();
        log.info("spotJobsExecutor created: core={} max={} queue={}", jobsPoolSize, jobsPoolSize, jobsQueueCapacity);
        return executor;
    }

    /** Lease renewals only. Single thread, prefix {@code spot-lease-hb}, so jobs can't starve heartbeats. */
    @Bean
    public ThreadPoolTaskScheduler spotLeaseHeartbeat() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("spot-lease-hb");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.initialize();
        return scheduler;
    }
}
