package com.xammer.cloud.service.autospotting.core;

import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseProperties;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Runs an AutoSpotting scheduled job body on {@code spotJobsExecutor} under its job lease
 * (design-core.md §3.5, §4.2, plan-phase-04.md task 4.2). Every later v2 {@code @Scheduled} method
 * calls {@link #runLeased} instead of running its body inline; the trigger thread returns at once
 * (AC-SPN-13: job bodies run on {@code spot-jobs-} threads, not scheduler threads). An ArchUnit rule
 * fails a v2 {@code @Scheduled} method that doesn't go through here.
 *
 * <p>Order of guards, matching §3.5:
 * <ol>
 *   <li>{@code scheduling.enabled} off → return at once (FR-SPN-10).</li>
 *   <li>Submit to {@code spotJobsExecutor}; a full queue (AbortPolicy) counts
 *       {@code autospotting.job.skipped{job,reason=busy}} and the next trigger retries.</li>
 *   <li>On a worker thread, take the job lease first; losing the race counts
 *       {@code autospotting.job.skipped{job,reason=lease_held}}, logged at debug.</li>
 *   <li>If {@code runKey} matches the stored {@code last_run_key}, skip (the run already happened),
 *       counting {@code autospotting.job.skipped{job,reason=already_run}}, and release keeping the
 *       minimum hold. Otherwise run the body, then release recording {@code runKey}.</li>
 * </ol>
 *
 * <p>Crons declare {@code zone = "UTC"} on their {@code @Scheduled} annotation (the JVM default is
 * Asia/Kolkata, F-05, NFR-07); that is the caller's responsibility, not this runner's.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    public static final String SKIPPED_METRIC = "autospotting.job.skipped";
    static final String REASON_BUSY = "busy";
    static final String REASON_LEASE_HELD = "lease_held";
    static final String REASON_ALREADY_RUN = "already_run";

    private final ThreadPoolTaskExecutor spotJobsExecutor;
    private final LeaseService leaseService;
    private final LeaseProperties leaseProperties;
    private final MeterRegistry meterRegistry;
    private final boolean schedulingEnabled;

    public JobRunner(ThreadPoolTaskExecutor spotJobsExecutor, LeaseService leaseService,
                     LeaseProperties leaseProperties, MeterRegistry meterRegistry,
                     @Value("${scheduling.enabled:true}") boolean schedulingEnabled) {
        this.spotJobsExecutor = spotJobsExecutor;
        this.leaseService = leaseService;
        this.leaseProperties = leaseProperties;
        this.meterRegistry = meterRegistry;
        this.schedulingEnabled = schedulingEnabled;
    }

    /**
     * Submit {@code body} to run under the {@code jobName} lease. {@code runKey} is the period key
     * (e.g. {@code 2026-10-06} for a daily job) used to skip a re-run within the day; pass null for a
     * job with no period key. Returns at once; the body runs on a {@code spot-jobs-} thread. Uses the
     * daily job timings (TTL {@link LeaseProperties#dailyTtl()}, heartbeat
     * {@link LeaseProperties#dailyHeartbeat()}, min hold {@link LeaseProperties#dailyMinHold()}).
     */
    public void runLeased(String jobName, String runKey, Runnable body) {
        runLeased(jobName, runKey, leaseProperties.dailyTtl(), leaseProperties.dailyHeartbeat(),
                leaseProperties.dailyMinHold(), body);
    }

    /**
     * As {@link #runLeased(String, String, Runnable)} but with explicit lease timings (the reconciler
     * uses its own TTL/heartbeat and no minimum hold, §3.3).
     */
    public void runLeased(String jobName, String runKey, Duration ttl, Duration heartbeat, Duration minHold,
                          Runnable body) {
        if (!schedulingEnabled) {
            return;
        }
        try {
            spotJobsExecutor.execute(() -> execute(jobName, runKey, ttl, heartbeat, minHold, body));
        } catch (TaskRejectedException rejected) {
            meterRegistry.counter(SKIPPED_METRIC, "job", jobName, "reason", REASON_BUSY).increment();
            log.debug("AutoSpotting job {} skipped: jobs executor busy", jobName);
        }
    }

    private void execute(String jobName, String runKey, Duration ttl, Duration heartbeat, Duration minHold,
                         Runnable body) {
        Optional<LeaseHandle> acquired = leaseService.acquireJob(jobName, ttl, heartbeat);
        if (acquired.isEmpty()) {
            meterRegistry.counter(SKIPPED_METRIC, "job", jobName, "reason", REASON_LEASE_HELD).increment();
            log.debug("AutoSpotting job {} skipped: lease held elsewhere", jobName);
            return;
        }
        LeaseHandle handle = acquired.get();
        String resolvedRunKey = runKey;
        try {
            if (runKey != null && runKey.equals(leaseService.lastRunKey(jobName))) {
                meterRegistry.counter(SKIPPED_METRIC, "job", jobName, "reason", REASON_ALREADY_RUN).increment();
                log.debug("AutoSpotting job {} skipped: run key {} already recorded", jobName, runKey);
                resolvedRunKey = null; // don't rewrite last_run_key for a skipped run
                return;
            }
            body.run();
        } finally {
            leaseService.releaseJob(handle, minHold, resolvedRunKey);
        }
    }
}
