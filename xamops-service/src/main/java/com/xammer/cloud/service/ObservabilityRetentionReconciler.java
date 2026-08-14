package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Applies data-retention settings to OpenObserve orgs in the background.
 *
 * Retention has to be pushed to O2 per stream, per stream type, per org. A tenant with a
 * few cloud accounts and ~30 streams each needs hundreds of sequential HTTP calls, which
 * is far too slow to run inside a request: with a 20s read timeout per call the worst case
 * comfortably exceeds nginx's 300s proxy_read_timeout and the user would watch a save
 * request hang and then fail.
 *
 * The database write is the part the user is waiting on, and it commits before this runs.
 * Pushing the same value again later is harmless, so losing a reconciliation to a restart
 * costs nothing beyond a delay until the next save.
 *
 * Lives in its own bean because {@code @Async} is proxy-based — calling an async method
 * from a sibling method of the same class silently runs it inline on the caller's thread.
 */
@Service
public class ObservabilityRetentionReconciler {

    private static final Logger logger = LoggerFactory.getLogger(ObservabilityRetentionReconciler.class);

    private final OpenObserveService openObserveService;

    public ObservabilityRetentionReconciler(OpenObserveService openObserveService) {
        this.openObserveService = openObserveService;
    }

    @Async
    public void applyRetentionToOrgs(List<String> orgIds, int retentionDays) {
        logger.info("Reconciling retention={}d across {} O2 org(s)", retentionDays, orgIds.size());

        for (String orgId : orgIds) {
            try {
                // Org-level default first: it governs streams the agents create later, so if
                // the per-stream pass below fails partway the new streams still inherit the
                // right value.
                openObserveService.applyOrgDefaultRetention(orgId, retentionDays);
                openObserveService.applyRetentionByType(orgId, "logs", retentionDays);
                openObserveService.applyRetentionByType(orgId, "metrics", retentionDays);
                openObserveService.applyRetentionByType(orgId, "traces", retentionDays);
            } catch (Exception e) {
                // One unreachable org must not stop the others.
                logger.warn("Retention reconciliation failed for org={}: {}", orgId, e.getMessage());
            }
        }

        logger.info("Retention reconciliation finished for {} org(s)", orgIds.size());
    }
}
