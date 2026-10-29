package com.xammer.cloud.autospotting.core;

import com.xammer.cloud.config.AsyncConfig;
import com.xammer.cloud.config.AutoSpottingExecutorConfig;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseProperties;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdcTaskDecorator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AutoSpottingExecutorConfig}, {@link AutoSpottingMdcTaskDecorator} and {@link JobRunner}
 * (plan-phase-04.md task 4.2, design-core.md §4.2). Asserts the thread-name prefixes (AC-SPN-13),
 * that the MDC keys are visible inside a submitted task, and that a rejected job is counted
 * {@code autospotting.job.skipped{reason=busy}}.
 */
class AutoSpottingExecutorConfigTest {

    private final TaskDecorator tenantDecorator = new AsyncConfig().tenantTaskDecorator();
    private final AutoSpottingMdcTaskDecorator mdcDecorator = new AutoSpottingMdcTaskDecorator();
    private final AutoSpottingExecutorConfig config = new AutoSpottingExecutorConfig(4, 3, 10, 3);

    private ThreadPoolTaskExecutor opsExecutor;
    private ThreadPoolTaskExecutor jobsExecutor;
    private ThreadPoolTaskScheduler dispatchScheduler;
    private ThreadPoolTaskScheduler taskScheduler;
    private ThreadPoolTaskScheduler leaseHeartbeat;

    @BeforeEach
    void setUp() {
        opsExecutor = config.spotOpsExecutor(tenantDecorator, mdcDecorator);
        jobsExecutor = config.spotJobsExecutor(tenantDecorator, mdcDecorator);
        dispatchScheduler = config.spotDispatchScheduler(tenantDecorator, mdcDecorator);
        taskScheduler = config.spotTaskScheduler(tenantDecorator, mdcDecorator);
        leaseHeartbeat = config.spotLeaseHeartbeat();
    }

    @AfterEach
    void tearDown() {
        opsExecutor.shutdown();
        jobsExecutor.shutdown();
        dispatchScheduler.shutdown();
        taskScheduler.shutdown();
        leaseHeartbeat.shutdown();
        MDC.clear();
    }

    @Test
    void threadNamePrefixesMatchTheDesign() throws Exception {
        assertThat(prefixOf(opsExecutor)).startsWith("spot-ops-");
        assertThat(prefixOf(jobsExecutor)).startsWith("spot-jobs-");
        assertThat(schedulerPrefix(dispatchScheduler)).startsWith("spot-dispatch-");
        assertThat(schedulerPrefix(taskScheduler)).startsWith("spot-sched-");
        assertThat(schedulerPrefix(leaseHeartbeat)).startsWith("spot-lease-hb");
    }

    @Test
    void opsExecutorIsFourFixedThreadsWithSynchronousQueue() {
        assertThat(opsExecutor.getCorePoolSize()).isEqualTo(4);
        assertThat(opsExecutor.getMaxPoolSize()).isEqualTo(4);
        // A SynchronousQueue has zero remaining capacity.
        assertThat(opsExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
    }

    @Test
    void mdcKeysAreVisibleInsideASubmittedTask() throws Exception {
        MDC.put(AutoSpottingMdcTaskDecorator.OPERATION_ID, "op-1");
        MDC.put(AutoSpottingMdcTaskDecorator.GROUP_ID, "grp-1");
        MDC.put(AutoSpottingMdcTaskDecorator.REQUEST_ID, "req-1");

        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        jobsExecutor.execute(() -> {
            seen.set(Map.of(
                    AutoSpottingMdcTaskDecorator.OPERATION_ID, String.valueOf(MDC.get(AutoSpottingMdcTaskDecorator.OPERATION_ID)),
                    AutoSpottingMdcTaskDecorator.GROUP_ID, String.valueOf(MDC.get(AutoSpottingMdcTaskDecorator.GROUP_ID)),
                    AutoSpottingMdcTaskDecorator.REQUEST_ID, String.valueOf(MDC.get(AutoSpottingMdcTaskDecorator.REQUEST_ID))));
            done.complete(null);
        });
        done.get(5, TimeUnit.SECONDS);

        assertThat(seen.get()).containsEntry(AutoSpottingMdcTaskDecorator.OPERATION_ID, "op-1")
                .containsEntry(AutoSpottingMdcTaskDecorator.GROUP_ID, "grp-1")
                .containsEntry(AutoSpottingMdcTaskDecorator.REQUEST_ID, "req-1");
    }

    @Test
    void mdcKeysAreClearedAfterTheTaskOnAPooledThread() throws Exception {
        // First task sets its own MDC; the decorator must restore (clear) the keys afterwards.
        MDC.put(AutoSpottingMdcTaskDecorator.OPERATION_ID, "op-A");
        CompletableFuture<Void> first = new CompletableFuture<>();
        jobsExecutor.execute(() -> first.complete(null));
        first.get(5, TimeUnit.SECONDS);
        MDC.clear();

        // Second task submitted with no MDC: the pooled thread must not leak op-A.
        AtomicReference<String> leaked = new AtomicReference<>("sentinel");
        CompletableFuture<Void> second = new CompletableFuture<>();
        jobsExecutor.execute(() -> {
            leaked.set(MDC.get(AutoSpottingMdcTaskDecorator.OPERATION_ID));
            second.complete(null);
        });
        second.get(5, TimeUnit.SECONDS);

        assertThat(leaked.get()).isNull();
    }

    @Test
    void jobRunnerCountsBusyWhenJobsQueueIsFull() throws Exception {
        LeaseService leaseService = mock(LeaseService.class);
        LeaseHandle handle = mock(LeaseHandle.class);
        LeaseProperties props = new LeaseProperties(120, 30, 600, 60, 1800, 60, 300, 10);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        // The job body blocks on a latch; the lease is always granted, so a submitted body occupies
        // a thread (or the queue). 3 threads + queue 10 = 13 occupied, then the 14th submit rejects.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(3);
        when(leaseService.acquireJob(any(), any(), any())).thenReturn(Optional.of(handle));
        when(leaseService.lastRunKey(any())).thenReturn(null);

        JobRunner runner = new JobRunner(jobsExecutor, leaseService, props, meters, true);
        Runnable blocking = () -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            for (int i = 0; i < 13; i++) {
                runner.runLeased("daily:fill-" + i, null, blocking);
            }
            // Wait until the 3 pool threads are running and all 10 queue slots are taken.
            started.await(5, TimeUnit.SECONDS);
            waitUntilBusy(jobsExecutor);
            runner.runLeased("daily:overflow", null, blocking);

            assertThat(meters.find(JobRunner.SKIPPED_METRIC)
                    .tag("job", "daily:overflow").tag("reason", "busy").counter().count())
                    .isEqualTo(1.0);
        } finally {
            release.countDown();
        }
    }

    private static void waitUntilBusy(ThreadPoolTaskExecutor executor) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            int active = executor.getThreadPoolExecutor().getActiveCount();
            int queued = executor.getThreadPoolExecutor().getQueue().size();
            if (active >= executor.getCorePoolSize() && queued >= 10) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private static String prefixOf(ThreadPoolTaskExecutor executor) throws Exception {
        AtomicReference<String> name = new AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        executor.execute(() -> {
            name.set(Thread.currentThread().getName());
            done.complete(null);
        });
        done.get(5, TimeUnit.SECONDS);
        return name.get();
    }

    private static String schedulerPrefix(ThreadPoolTaskScheduler scheduler) throws Exception {
        AtomicReference<String> name = new AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        scheduler.schedule(() -> {
            name.set(Thread.currentThread().getName());
            done.complete(null);
        }, java.time.Instant.now());
        done.get(5, TimeUnit.SECONDS);
        return name.get();
    }
}
