package com.xammer.cloud.controller;

import com.xammer.cloud.domain.AwsInventorySyncState;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.inventory.AwsInventoryReconciler;
import com.xammer.cloud.service.inventory.AwsInventoryScheduler;
import com.xammer.cloud.service.inventory.InventoryProjectionService;
import com.xammer.cloud.service.inventory.InventoryQueueAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Operational surface for the inventory plane.
 *
 * <p>
 * Exists because the plane fails <em>soft</em>: when Resource Explorer is blocked or an
 * index is still building, pages silently keep using the old describe path. That is the
 * right behaviour for users and a debugging problem for us — without this endpoint the
 * only way to tell which path an account is on is to grep logs. {@code /status} answers
 * that in one call, and is the check to run before enabling the flag in an environment.
 */
@RestController
@RequestMapping("/api/xamops/inventory")
public class InventoryPlaneController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryPlaneController.class);

    private final InventoryProjectionService projection;
    private final AwsInventoryScheduler scheduler;
    private final CloudAccountRepository cloudAccountRepository;
    private final InventoryQueueAuthorizer queueAuthorizer;

    public InventoryPlaneController(InventoryProjectionService projection,
            AwsInventoryScheduler scheduler,
            CloudAccountRepository cloudAccountRepository,
            InventoryQueueAuthorizer queueAuthorizer) {
        this.projection = projection;
        this.scheduler = scheduler;
        this.cloudAccountRepository = cloudAccountRepository;
        this.queueAuthorizer = queueAuthorizer;
    }

    /**
     * Whether this account is being served by the plane, and if not, why.
     *
     * <p>
     * {@code servingFromPlane} is the headline: false means the account is still paying
     * for the full describe fan-out. {@code indexType=PARTIAL} means the numbers are
     * real but incomplete, which is the case worth catching before a customer does.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestParam String accountId) {
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", accountId);

        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByProviderAccountId(accountId);
        if (accountOpt.isEmpty()) {
            body.put("servingFromPlane", false);
            body.put("mode", "ACCOUNT_NOT_FOUND");
            body.put("hint", "No cloud_account row for " + accountId
                    + ". The plane is keyed on the connection, not the AWS account id.");
            return ResponseEntity.ok(body);
        }
        Long cloudAccountId = accountOpt.get().getId();
        body.put("cloudAccountId", cloudAccountId);

        boolean fresh = projection.isFresh(cloudAccountId);
        body.put("servingFromPlane", fresh);
        body.put("partialResults", projection.isPartial(cloudAccountId));

        Optional<AwsInventorySyncState> stateOpt = projection.syncState(cloudAccountId);
        if (stateOpt.isEmpty()) {
            body.put("mode", "NEVER_SYNCED");
            body.put("hint", "No sync has run. Check xamops.inventory.enabled and that the "
                    + "account is CONNECTED with a role ARN.");
            return ResponseEntity.ok(body);
        }

        AwsInventorySyncState state = stateOpt.get();
        body.put("mode", state.getMode());
        body.put("indexType", state.getIndexType());
        body.put("aggregatorRegion", state.getAggregatorRegion());
        body.put("resourceCount", state.getResourceCount());
        body.put("syncGeneration", state.getSyncGeneration());
        body.put("lastFullSyncAt", state.getLastFullSyncAt());
        body.put("lastSyncDurationMs", state.getLastSyncDurationMs());
        body.put("lastError", state.getLastError());

        // Event liveness. Distinguishing "events never configured" from "events
        // configured but stopped arriving" is the difference between a customer who
        // never deployed the optional stack and a broken forwarder Lambda — and only
        // the second one is our problem to fix.
        body.put("lastEventAt", state.getLastEventAt());
        // Is this account authorised on the queue? Answers "did onboarding wire the
        // event path up", which is otherwise invisible until events fail to arrive.
        boolean authorized = queueAuthorizer.authorizedAccounts().contains(accountId);
        body.put("queueAuthorized", authorized);

        if (state.getLastEventAt() == null) {
            body.put("eventPipeline", "NOT_RECEIVING");
            body.put("freshness", "~60s (polling only)");
            if (!authorized) {
                body.put("eventHint", "Account is not authorised on the inventory queue. "
                        + "Check the backend role has sqs:SetQueueAttributes, then restart or "
                        + "wait for the hourly authorisation sync.");
            }
        } else {
            long minutesSinceEvent = java.time.temporal.ChronoUnit.MINUTES.between(
                    state.getLastEventAt(), java.time.Instant.now());
            // A quiet account legitimately produces no events for hours, so this is a
            // hint rather than an alarm.
            body.put("eventPipeline", minutesSinceEvent <= 60 ? "ACTIVE" : "QUIET");
            body.put("minutesSinceLastEvent", minutesSinceEvent);
            body.put("freshness", "~5s (event-driven)");
        }

        body.put("uncoveredRegions", state.getUncoveredRegions());

        if (AwsInventorySyncState.INDEX_AGGREGATOR_INCOMPLETE.equals(state.getIndexType())) {
            body.put("hint", "Enabled region(s) [" + state.getUncoveredRegions() + "] have no "
                    + "Resource Explorer index, so their resources are invisible to the fast "
                    + "path — AWS does not auto-index opt-in regions. The account is being "
                    + "served by the describe fallback instead, which is complete but slower. "
                    + "Fix: create a Resource Explorer index in those regions (free), e.g. "
                    + "aws resource-explorer-2 create-index --region <region>, then it will "
                    + "replicate into the aggregator and this account moves to the fast path.");
        } else if (AwsInventorySyncState.INDEX_PARTIAL.equals(state.getIndexType())) {
            body.put("hint", "Partial results: no service-linked role in the customer account, "
                    + "so untagged resources created before Oct 2025 are missing. Re-deploy the "
                    + "onboarding stack to grant iam:CreateServiceLinkedRole.");
        } else if (AwsInventorySyncState.INDEX_LOCAL.equals(state.getIndexType())) {
            body.put("hint", "Local index only: results cover one region. Promoting an aggregator "
                    + "index makes this a single cross-region query.");
        }
        return ResponseEntity.ok(body);
    }

    /** Per-type counts straight from SQL, for spot-checking against the AWS console. */
    @GetMapping("/counts")
    public ResponseEntity<Map<String, Long>> counts(@RequestParam String accountId) {
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .map(a -> ResponseEntity.ok(projection.countsByType(a.getId())))
                .orElseGet(() -> ResponseEntity.ok(Map.of()));
    }

    /** Regions containing resources, derived from the table rather than probed. */
    @GetMapping("/regions")
    public ResponseEntity<List<String>> regions(@RequestParam String accountId) {
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .map(a -> ResponseEntity.ok(projection.activeRegions(a.getId())))
                .orElseGet(() -> ResponseEntity.ok(List.of()));
    }

    /**
     * Resources first seen in the last N minutes.
     *
     * <p>
     * This is the endpoint that proves the feature: create something in AWS, call this,
     * and it should appear within a sync interval.
     */
    @GetMapping("/recent")
    public ResponseEntity<List<ResourceDto>> recent(@RequestParam String accountId,
            @RequestParam(defaultValue = "60") int withinMinutes) {
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .map(a -> ResponseEntity.ok(projection.recentlyAdded(a.getId(), withinMinutes)))
                .orElseGet(() -> ResponseEntity.ok(List.of()));
    }

    /** Forces a sync now instead of waiting for the poll. Used by the refresh button. */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncNow(@RequestParam String accountId) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByProviderAccountId(accountId);
        if (accountOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Account not found: " + accountId));
        }

        AwsInventoryReconciler.SyncResult result = scheduler.syncNow(accountOpt.get());
        logger.info("[Inventory] Manual sync for {} — success={} observed={} created={} swept={}",
                accountId, result.success, result.observed, result.created, result.swept);

        Map<String, Object> body = new HashMap<>();
        body.put("success", result.success);
        body.put("mode", result.mode);
        body.put("indexType", result.indexType);
        body.put("observed", result.observed);
        body.put("created", result.created);
        body.put("removed", result.swept);
        body.put("durationMs", result.durationMs);
        body.put("error", result.error);
        return ResponseEntity.ok(body);
    }
}
