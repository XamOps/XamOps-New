package com.xammer.cloud.service.inventory;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import com.xammer.cloud.repository.AwsInventorySyncStateRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CloudSecurityFindingRepository;
import com.xammer.cloud.repository.SecurityNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives the Resource Explorer sync.
 *
 * <p>
 * Runs on a short fixed delay rather than a nightly cron, which is the whole point of
 * the change: AWS inventory previously had no scheduled refresh at all — GCP had
 * {@code 0 8 3 * * *} and Azure {@code 0 0 5 * * WED,SUN}, but AWS refreshed only when a
 * user clicked. A one-minute poll is affordable here precisely because Resource Explorer
 * is free and answers in a handful of calls; polling the old 1000-call describe fan-out
 * this often would be reckless.
 *
 * <p>
 * Deliberately its own scheduler rather than another method on
 * {@code ProactiveCacheRefreshService}: everything there is a heavyweight twice-weekly
 * job, and mixing a per-minute task into that class would invite someone to "tidy" it
 * onto the same cron. It also keeps this independently switchable.
 */
@Service
public class AwsInventoryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AwsInventoryScheduler.class);

    /** Pushed to the UI so an open cloud list can refresh itself without polling. */
    private static final String TOPIC = "/topic/inventory-refresh";

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsInventoryReconciler reconciler;
    private final SchedulingEnvironmentConfig envConfig;
    private final AwsCloudResourceRepository resourceRepository;
    private final AwsInventorySyncStateRepository syncStateRepository;
    private final InventoryChangeNotifier notifier;
    private final CloudSecurityFindingRepository findingRepository;
    private final SecurityNotificationRepository notificationRepository;

    @Value("${xamops.inventory.enabled:false}")
    private boolean enabled;

    @Value("${xamops.security.live-findings.retention-days:90}")
    private long findingRetentionDays;

    @Value("${xamops.security.notify.inbox-retention-days:30}")
    private long inboxRetentionDays;

    /**
     * Guard against overlap. Spring's scheduler is single-threaded per task, but a sync
     * pass that outruns its own interval would otherwise queue up behind itself; this
     * makes a slow pass skip a beat instead of building a backlog.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AwsInventoryScheduler(CloudAccountRepository cloudAccountRepository,
            AwsInventoryReconciler reconciler,
            SchedulingEnvironmentConfig envConfig,
            AwsCloudResourceRepository resourceRepository,
            AwsInventorySyncStateRepository syncStateRepository,
            InventoryChangeNotifier notifier,
            CloudSecurityFindingRepository findingRepository,
            SecurityNotificationRepository notificationRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.reconciler = reconciler;
        this.envConfig = envConfig;
        this.resourceRepository = resourceRepository;
        this.syncStateRepository = syncStateRepository;
        this.notifier = notifier;
        this.findingRepository = findingRepository;
        this.notificationRepository = notificationRepository;
    }

    /**
     * Removes inventory belonging to connections that no longer exist.
     *
     * <p>
     * Disconnecting an account deletes its {@code cloud_account} row but not its
     * resources, and reconnecting allocates a new id rather than reusing the old one. The
     * abandoned rows are invisible to every scoped read, and because no scheduler runs for
     * a connection that is gone, the mark-and-sweep that would normally retire them never
     * fires again — so they would survive indefinitely. Hourly is ample: nothing reads
     * them, this only reclaims space.
     */
    @Scheduled(initialDelay = 300_000, fixedDelay = 3_600_000)
    @Transactional
    public void purgeOrphanedInventory() {
        if (!enabled || !envConfig.isSchedulingEnabled()) {
            return;
        }
        try {
            TenantContext.setCurrentTenant(envConfig.getCurrentTenantId());
            try {
                int rows = resourceRepository.deleteOrphanedByMissingAccount();
                int states = syncStateRepository.deleteOrphanedByMissingAccount();
                if (rows > 0 || states > 0) {
                    log.info("[Inventory] Purged {} orphaned resource row(s) and {} sync state(s) "
                            + "from disconnected accounts", rows, states);
                }

                // Security findings share the orphan problem exactly — same cause, same
                // invisibility, same unbounded growth — so they are reclaimed on the same
                // pass rather than in a second scheduler that could drift out of step.
                int orphanedFindings = findingRepository.deleteOrphanedByMissingAccount();
                int purgedFindings = findingRepository.purgeResolvedBefore(
                        java.time.Instant.now().minus(findingRetentionDays, java.time.temporal.ChronoUnit.DAYS));
                if (orphanedFindings > 0 || purgedFindings > 0) {
                    log.info("[SecFinding] Purged {} orphaned and {} resolved finding(s) older "
                            + "than {} days", orphanedFindings, purgedFindings, findingRetentionDays);
                }

                // The inbox has a shorter retention than the findings themselves: it is a feed,
                // and the findings table is the durable record something can be looked up in.
                int purgedNotifications = notificationRepository.purgeBefore(
                        java.time.Instant.now().minus(inboxRetentionDays, java.time.temporal.ChronoUnit.DAYS));
                if (purgedNotifications > 0) {
                    log.info("[SecNotify] Purged {} notification(s) older than {} days",
                            purgedNotifications, inboxRetentionDays);
                }
            } finally {
                TenantContext.clear();
            }
        } catch (Exception e) {
            log.warn("[Inventory] Orphan purge failed: {}", e.getMessage());
        }
    }

    /**
     * Syncs every connected AWS account.
     *
     * <p>
     * Default 60s. This is the knob that trades freshness against load: at 60s a new
     * resource surfaces within a minute, which is what makes the feature feel immediate
     * without the EventBridge stack. The event listener later drops this to seconds and
     * this poll becomes pure reconciliation, at which point the interval can be relaxed.
     */
    @Scheduled(fixedDelayString = "${xamops.inventory.sync-interval-ms:60000}",
            initialDelayString = "${xamops.inventory.initial-delay-ms:120000}")
    public void syncAllAccounts() {
        if (!enabled) {
            return;
        }
        if (!envConfig.isSchedulingEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("[Inventory] Previous sync pass still running — skipping this cycle");
            return;
        }

        long startMs = System.currentTimeMillis();
        int synced = 0;
        int fellBack = 0;

        try {
            String tenantId = envConfig.getCurrentTenantId();
            TenantContext.setCurrentTenant(tenantId);
            try {
                List<CloudAccount> accounts = cloudAccountRepository.findAll();
                for (CloudAccount account : accounts) {
                    if (!isSyncableAwsAccount(account)) {
                        continue;
                    }
                    try {
                        AwsInventoryReconciler.SyncResult result = reconciler.sync(account);
                        if (result.success) {
                            synced++;
                            // Only notify when something actually moved, so an idle
                            // account does not push a message every minute to every
                            // connected browser.
                            if (result.created > 0 || result.swept > 0) {
                                notifyChanged(account, result);
                            }
                        } else {
                            fellBack++;
                        }
                    } catch (Exception e) {
                        // Already handled inside sync(); this is belt-and-braces so one
                        // account can never end the pass for the others.
                        fellBack++;
                        log.error("[Inventory] Unexpected failure syncing account {}: {}",
                                account.getAwsAccountId(), e.getMessage());
                    }
                }
            } finally {
                TenantContext.clear();
            }

            if (synced > 0 || fellBack > 0) {
                log.debug("[Inventory] Sync pass done in {}ms — {} synced, {} on describe fallback",
                        System.currentTimeMillis() - startMs, synced, fellBack);
            }
        } catch (Exception e) {
            log.error("[Inventory] Sync pass aborted: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    /**
     * Only CONNECTED AWS accounts are worth a call. A PENDING or FAILED account has no
     * usable role, so syncing it would just generate STS failures every minute.
     */
    private boolean isSyncableAwsAccount(CloudAccount account) {
        return "AWS".equals(account.getProvider())
                && account.getAwsAccountId() != null
                && !account.getAwsAccountId().isBlank()
                && "CONNECTED".equals(account.getStatus())
                && account.getRoleArn() != null;
    }

    private void notifyChanged(CloudAccount account, AwsInventoryReconciler.SyncResult result) {
        // Delegated so the cloud list topic and the dashboard widget topic are always
        // published together. See InventoryChangeNotifier.
        notifier.inventoryChanged(account, result.created, result.swept, result.observed, "poll");
        log.info("[Inventory] Account {} changed — {} added, {} removed; pushed to {}",
                account.getAwsAccountId(), result.created, result.swept, TOPIC);
    }

    /** Manual trigger for the refresh button and for onboarding a new account. */
    public AwsInventoryReconciler.SyncResult syncNow(CloudAccount account) {
        AwsInventoryReconciler.SyncResult result = reconciler.sync(account);
        if (result.success) {
            notifyChanged(account, result);
        }
        return result;
    }
}
