package com.xammer.cloud.service.call;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives the call sweeps on a thread of their own.
 *
 * These two sweeps are the only thing standing between a vanished browser and a
 * ticket that can never be called again, and they are also what makes a missed
 * call reliable — so they have to run close to on time.
 *
 * The shared {@code taskScheduler} cannot promise that. It has five threads and
 * this service has more than forty classes with {@code @Scheduled} methods on it,
 * a number of which make long serial cloud API calls. A sweep queued behind an
 * inventory scan is a ring that expires minutes late, or an abandoned room that
 * keeps its ticket locked for far longer than the staleness window implies. A
 * single dedicated thread costs almost nothing and removes that coupling.
 *
 * Every instance sweeps. That is safe by construction: both sweeps close sessions
 * through the conditional {@code terminate} update, so concurrent sweepers on
 * different replicas cannot both act on the same call.
 */
@Component
public class CallSweeperScheduler {

    private static final Logger log = LoggerFactory.getLogger(CallSweeperScheduler.class);

    private final CallService callService;

    @Value("${xamops.calls.sweep-interval-ms:10000}")
    private long sweepIntervalMs;

    private ScheduledExecutorService executor;

    /**
     * Injected rather than self-invoked so the calls pass through the Spring proxy —
     * both sweeps are {@code @Transactional}, and calling them on {@code this} would
     * quietly skip transaction management.
     */
    public CallSweeperScheduler(CallService callService) {
        this.callService = callService;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "call-sweeper");
            // Daemon so a shutdown is never held up waiting for the next tick.
            t.setDaemon(true);
            return t;
        });

        // Each sweep is wrapped individually: scheduleWithFixedDelay cancels the task
        // permanently if it ever throws, so an unhandled exception would silently stop
        // sweeping for the lifetime of the process — reintroducing exactly the bug this
        // exists to prevent, and with no signal that it had happened.
        executor.scheduleWithFixedDelay(guard("ring expiry", callService::sweepExpiredRings),
                sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(guard("abandoned calls", callService::sweepAbandonedCalls),
                sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS);

        log.info("Call sweeper started on a dedicated thread, every {}ms", sweepIntervalMs);
    }

    private Runnable guard(String label, Runnable sweep) {
        return () -> {
            try {
                sweep.run();
            } catch (Exception e) {
                log.warn("Call sweep '{}' failed; will retry on the next tick: {}", label, e.getMessage());
            } catch (Throwable t) {
                // Errors are logged and swallowed for the same reason: losing the sweeper
                // is a worse outcome than whatever caused this.
                log.error("Call sweep '{}' hit a fatal error; will retry on the next tick", label, t);
            }
        };
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
