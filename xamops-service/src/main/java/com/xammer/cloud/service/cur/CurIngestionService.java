package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CurSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Re-validates every configured CUR daily, so the read path always knows whether
 * a report is still being delivered and what it currently contains.
 *
 * <h2>Why daily, when the AWS cost refresh is WED/SUN</h2>
 * {@code ProactiveCacheRefreshService.refreshCostData()} runs twice a week because
 * Cost Explorer bills $0.01 per request and the job makes several per account.
 * Reading a CUR has no per-request charge — the cost is Athena scan volume, which
 * for a partition-pruned monthly aggregate is a fraction of a cent. Twice-weekly
 * was a workaround for CE pricing, so it does not carry over.
 *
 * <p>Daily also matches what the other two providers already do: GCP billing
 * ingestion runs daily and Azure every six hours, both for exactly this reason —
 * they read an export rather than a metered API.
 *
 * <h2>What "ingestion" means here</h2>
 * Nothing is copied. AWS rewrites the whole month-to-date dataset in place up to
 * three times a day, so there is no delta to consume and no local copy worth
 * keeping. This job re-reads each manifest to refresh the discovered metadata and
 * the {@code lastValidatedAt} stamp the resolver uses to detect a report that has
 * stopped arriving; the cost figures themselves are queried on demand and cached
 * by the existing Redis/CachedData layer.
 */
@Service
public class CurIngestionService {

    private static final Logger logger = LoggerFactory.getLogger(CurIngestionService.class);

    /**
     * Minimum gap between on-demand attempts on one source.
     *
     * <p>A source can be stale because the report really has stopped arriving, and
     * that state is reached through the same branch as a laptop that was asleep at
     * 01:45. Without a floor, every page load on a genuinely broken report would
     * start another manifest read and Athena probe.
     */
    private static final Duration RETRY_AFTER = Duration.ofMinutes(10);

    private final CurSourceRepository curSourceRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final CurSourceValidationService validationService;
    private final Executor executor;

    @Value("${xamops.cur.enabled:false}")
    private boolean curEnabled;

    /** Must match {@link CurSourceResolver}'s limit, or this would repair the wrong sources. */
    @Value("${xamops.cur.max-validation-age-hours:48}")
    private long maxValidationAgeHours;

    /** When each source was last attempted, so a broken one is not retried per request. */
    private final Map<Long, Long> lastAttempt = new ConcurrentHashMap<>();

    /** Sources with an attempt already running, so a page of six sections starts one. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public CurIngestionService(CurSourceRepository curSourceRepository,
                               CloudAccountRepository cloudAccountRepository,
                               CurSourceValidationService validationService,
                               @Qualifier("awsTaskExecutor") Executor executor) {
        this.curSourceRepository = curSourceRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.validationService = validationService;
        this.executor = executor;
    }

    /**
     * Revalidates anything already stale when the application starts.
     *
     * <p>{@link #revalidateAll()} is the only thing that refreshes
     * {@code lastValidatedAt}, and it runs at 01:45. An instance that is not running
     * at 01:45 — every developer laptop, and any deployment restarted outside that
     * window — therefore ages every source past
     * {@code xamops.cur.max-validation-age-hours} and keeps it there, at which point
     * {@link CurSourceResolver} refuses a report that is being delivered perfectly
     * normally. The cost pages fall back to Cost Explorer and the CUR-only sections,
     * which have no fallback, return 409 indefinitely.
     *
     * <p>Checking at startup costs one manifest read per stale source and removes the
     * whole class of failure, so there is no reason to wait for the small hours.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void revalidateStaleOnStartup() {
        if (!curEnabled) {
            return;
        }
        // Off the startup thread: each source costs an S3 listing, a manifest read and
        // an Athena probe, and none of that should hold up the application being ready.
        try {
            executor.execute(this::revalidateStale);
        } catch (Exception e) {
            logger.warn("[CUR] Could not schedule startup revalidation: {}", e.getMessage());
        }
    }

    /** Revalidates every source that is stale or not ACTIVE, leaving healthy ones alone. */
    public void revalidateStale() {
        List<CurSource> due = curSourceRepository.findAll().stream()
                .filter(source -> !source.isActive() || isStale(source))
                .toList();
        if (due.isEmpty()) {
            logger.debug("[CUR] No sources need revalidation");
            return;
        }

        logger.info("[CUR] {} source(s) stale or inactive at startup — revalidating", due.size());
        for (CurSource source : due) {
            attempt(source.getId());
        }
    }

    /**
     * Asks for one source to be revalidated behind the current request.
     *
     * <p>Called by the resolver the moment it rejects a source as stale, so a running
     * instance repairs itself rather than serving Cost Explorer until 01:45. The caller
     * is not made to wait: this request still falls back, and the one after it reads the
     * report.
     *
     * <p>Rate-limited per source by {@link #RETRY_AFTER} and single-flighted, because the
     * Cost page asks six sections at once and each one would otherwise trigger its own
     * manifest read.
     */
    public void requestRevalidation(Long sourceId) {
        if (!curEnabled || sourceId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = lastAttempt.get(sourceId);
        if (previous != null && now - previous < RETRY_AFTER.toMillis()) {
            return;
        }
        if (!inFlight.add(sourceId)) {
            return;
        }

        try {
            executor.execute(() -> {
                try {
                    attempt(sourceId);
                } finally {
                    inFlight.remove(sourceId);
                }
            });
        } catch (Exception e) {
            inFlight.remove(sourceId);
            logger.debug("[CUR] Could not schedule revalidation for source {}: {}",
                    sourceId, e.getMessage());
        }
    }

    /**
     * Revalidates one source by id, re-reading it first.
     *
     * <p>By id rather than by the instance the caller held: the resolver hands over a
     * source loaded on a request thread, and this runs on a pool thread after that
     * session has closed.
     */
    private void attempt(Long sourceId) {
        // Stamped here rather than at the request, so the startup sweep and an on-demand
        // ask share one floor — otherwise a source the sweep has just failed to repair
        // gets tried again by the first page load a moment later.
        lastAttempt.put(sourceId, System.currentTimeMillis());
        try {
            curSourceRepository.findById(sourceId).ifPresent(source -> {
                CurSource validated = revalidate(source);
                if (validated.isActive()) {
                    logger.info("✅ [CUR] Source {} revalidated — reads resume from the report",
                            sourceId);
                } else {
                    logger.warn("[CUR] Source {} still unusable after revalidation: {}",
                            sourceId, validated.getStatusDetail());
                }
            });
        } catch (Exception e) {
            logger.warn("[CUR] Revalidation failed for source {}: {}", sourceId, e.getMessage());
        }
    }

    /** Same test {@link CurSourceResolver} applies before refusing a source. */
    private boolean isStale(CurSource source) {
        LocalDateTime validated = source.getLastValidatedAt();
        return validated == null
                || validated.isBefore(LocalDateTime.now().minusHours(maxValidationAgeHours));
    }

    /**
     * 01:45, ahead of the 02:00 FinOps and 02:30 cost refreshes.
     *
     * <p>Ordering matters: those jobs warm the cost caches, and a source that
     * failed validation must already be marked FAILED by the time they run, so they
     * fall back to Cost Explorer instead of warming a cache from a broken CUR.
     */
    @Scheduled(cron = "0 45 1 * * *")
    public void revalidateAll() {
        if (!curEnabled) {
            logger.debug("[CUR] Ingestion skipped — xamops.cur.enabled is false");
            return;
        }

        // FAILED sources are retried too: the usual causes are transient (a delivery
        // that had not landed yet, a policy not applied at onboarding time), and a
        // source that never retries stays broken until someone notices.
        List<CurSource> sources = curSourceRepository.findAll();
        logger.info("[CUR] Revalidating {} source(s)", sources.size());

        int active = 0;
        for (CurSource source : sources) {
            try {
                CurSource validated = revalidate(source);
                if (validated.isActive()) {
                    active++;
                }
            } catch (Exception e) {
                logger.error("[CUR] Revalidation failed for source {}: {}", source.getId(), e.getMessage(), e);
            }
        }
        logger.info("[CUR] Revalidation complete — {}/{} sources ACTIVE", active, sources.size());
    }

    /**
     * Revalidates one source.
     *
     * <p>A customer-owned bucket needs an account whose role can read it. Any
     * account attached to the source will do — they all sit under the same report —
     * so the first one is used. A XamOps-owned source needs none.
     */
    public CurSource revalidate(CurSource source) {
        CloudAccount account = null;
        if (!source.isXamopsOwned()) {
            List<CloudAccount> accounts = cloudAccountRepository.findByCurSourceId(source.getId());
            if (accounts.isEmpty()) {
                logger.warn("[CUR] Source {} is customer-owned but no account references it — cannot validate",
                        source.getId());
                return source;
            }
            account = accounts.get(0);
        }
        return validationService.validate(source, account);
    }
}
