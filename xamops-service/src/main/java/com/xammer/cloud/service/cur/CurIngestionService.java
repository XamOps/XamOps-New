package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CurSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

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

    private final CurSourceRepository curSourceRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final CurSourceValidationService validationService;

    @Value("${xamops.cur.enabled:false}")
    private boolean curEnabled;

    public CurIngestionService(CurSourceRepository curSourceRepository,
                               CloudAccountRepository cloudAccountRepository,
                               CurSourceValidationService validationService) {
        this.curSourceRepository = curSourceRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.validationService = validationService;
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
