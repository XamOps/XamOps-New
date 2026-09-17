package com.xammer.cloud.service.azure;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.event.CloudAccountConnectedEvent;
import com.xammer.cloud.repository.CloudAccountRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Builds the first Azure dashboard snapshot for a newly connected subscription.
 *
 * <p>
 * Without this, nothing between onboarding and the next scheduled run ever populated
 * {@code AZURE_DASHBOARD::{subscriptionId}}. A customer finished connecting Azure and landed on a
 * dashboard of zeros, and the only thing that would fix it was a scheduled job up to a day away —
 * previously up to three and a half days, since that job ran on Wednesdays and Sundays. AWS has
 * never had this gap: {@code AwsInventoryScheduler.syncNow} runs on demand and the inventory plane
 * is populated within minutes of connecting.
 *
 * <p>
 * A listener on {@link CloudAccountConnectedEvent} rather than a call inside
 * {@code AccountManagerController#addAzureAccount}, for the reason the event exists: onboarding
 * should not grow a dependency on the dashboard, the billing pipeline, and everything else that
 * wants to react to a new account. {@code WarOnboardingReportService} already listens to the same
 * event for the same reason.
 */
@Service
public class AzureDashboardWarmupListener {

    private static final Logger log = LoggerFactory.getLogger(AzureDashboardWarmupListener.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AzureDashboardService azureDashboardService;
    private final SchedulingEnvironmentConfig envConfig;

    /**
     * Delay before the first refresh starts.
     *
     * <p>
     * The connected event is published before {@code setupCostExports} has run and saved
     * {@code azureBillingContainer}, so refreshing instantly would build a snapshot for a
     * subscription that does not yet look like it has a cost export — correct at that instant, and
     * misleading a minute later. Waiting lets onboarding finish writing, so the first snapshot has
     * cost data in it rather than a "cost reporting is not configured" warning the customer sees
     * before it is even true.
     *
     * <p>
     * If the export genuinely failed, the wait changes nothing: the warning is accurate and stays.
     */
    @Value("${xamops.azure.dashboard.warmup-delay-seconds:120}")
    private long warmupDelaySeconds;

    /**
     * Own thread rather than the shared scheduler or {@code backgroundTaskExecutor}.
     *
     * <p>
     * {@code spring.task.scheduling.pool.size=1} in prod, so scheduling a delayed one-off there
     * would hold the single scheduler thread for the whole refresh and push every cron behind it.
     * And a delay implemented by sleeping on {@code backgroundTaskExecutor} would park a pool
     * thread for minutes doing nothing. One dedicated daemon thread costs nothing and cannot
     * interfere with either.
     */
    private final ScheduledExecutorService warmupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "azure-dashboard-warmup");
        t.setDaemon(true);
        return t;
    });

    public AzureDashboardWarmupListener(CloudAccountRepository cloudAccountRepository,
            AzureDashboardService azureDashboardService,
            SchedulingEnvironmentConfig envConfig) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.azureDashboardService = azureDashboardService;
        this.envConfig = envConfig;
    }

    /**
     * Queues the first refresh once the onboarding transaction has committed.
     *
     * <p>
     * {@code AFTER_COMMIT} matters: the worker re-reads the account by id on another thread, and
     * before the commit that row is not visible to it. {@code fallbackExecution = true} because
     * {@code addAzureAccount} is not itself transactional, and without the fallback the event
     * would be dropped silently rather than delivered inline.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountConnected(CloudAccountConnectedEvent event) {
        if (!"Azure".equalsIgnoreCase(event.provider())) {
            return;
        }

        // Captured on the request thread. The worker runs on a plain executor with no
        // TaskDecorator — unlike awsTaskExecutor, backgroundTaskExecutor does not carry
        // TenantContext either — so without this the repository read would route to the default
        // datasource instead of the tenant's.
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            tenantId = envConfig.getCurrentTenantId();
        }
        final String resolvedTenant = tenantId;
        final Long accountId = event.cloudAccountId();

        try {
            warmupExecutor.schedule(
                    () -> warmUp(accountId, resolvedTenant),
                    warmupDelaySeconds, TimeUnit.SECONDS);
            log.info("[Azure-warmup] First dashboard refresh queued for cloud account {} in {}s",
                    accountId, warmupDelaySeconds);
        } catch (Exception e) {
            // Onboarding has already succeeded and the customer has been told so. A failure to
            // queue the warm-up must not surface as a failed connection; the scheduled refresh
            // remains the backstop.
            log.warn("[Azure-warmup] Could not queue first refresh for cloud account {}: {}",
                    accountId, e.getMessage());
        }
    }

    /**
     * Resolves the account and builds its first snapshot.
     *
     * <p>
     * Package-private so a test can drive it without waiting out the delay.
     */
    void warmUp(Long cloudAccountId, String tenantId) {
        TenantContext.setCurrentTenant(tenantId);
        try {
            Optional<CloudAccount> found = cloudAccountRepository.findById(cloudAccountId);
            if (found.isEmpty()) {
                log.warn("[Azure-warmup] cloud account {} no longer exists — skipping", cloudAccountId);
                return;
            }
            CloudAccount account = found.get();

            String subscriptionId = account.getAzureSubscriptionId();
            if (subscriptionId == null || subscriptionId.isBlank()) {
                log.warn("[Azure-warmup] cloud account {} has no subscription id — skipping", cloudAccountId);
                return;
            }
            if (!"CONNECTED".equals(account.getStatus())) {
                log.info("[Azure-warmup] account {} is {}, not CONNECTED — skipping",
                        cloudAccountId, account.getStatus());
                return;
            }

            log.info("[Azure-warmup] Building first dashboard snapshot for subscription {}", subscriptionId);
            long startMs = System.currentTimeMillis();
            azureDashboardService.refreshAndCacheDashboard(subscriptionId);
            log.info("[Azure-warmup] First snapshot ready for subscription {} in {}ms",
                    subscriptionId, System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            // The scheduled refresh will pick this subscription up on its next run, and the
            // dashboard already reports "no snapshot yet" honestly in the meantime. Nothing here
            // is worth failing loudly over.
            log.error("[Azure-warmup] First dashboard refresh failed for cloud account {} — the scheduled "
                    + "refresh remains the fallback.", cloudAccountId, e);
        } finally {
            TenantContext.clear();
        }
    }

    @PreDestroy
    void shutdown() {
        warmupExecutor.shutdownNow();
    }
}
