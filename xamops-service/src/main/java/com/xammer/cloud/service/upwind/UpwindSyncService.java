package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.*;
import com.xammer.cloud.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;

/**
 * Pulls Upwind findings into XamOps tables.
 *
 * Detections: polled frequently (no server-side time filter confirmed yet, so
 * we fetch recent pages and upsert by upwind_id — volumes are small).
 * Vulnerabilities/posture: full sync; anything not seen in the latest full
 * sync is marked RESOLVED.
 */
@Service
@Slf4j
public class UpwindSyncService {

    // Page caps are in PAGES; the client requests UpwindApiClient.PAGE_SIZE (1000) per page.
    static final int DETECTION_POLL_MAX_PAGES = 5;    // ~5k newest detections per poll
    /**
     * Runaway guard, in PAGES of {@link UpwindApiClient#PAGE_SIZE} (1000) items.
     *
     * Raised from 200 after measuring 2026-08-05: vulnerability-findings returns 161,424
     * findings for ONE account (Onfinance, 8,499 unique CVEs), and the vulnerability walk is
     * org-wide with no account filter, so the org total already exceeds the old 200-page /
     * 200k-item ceiling. Hitting it is not a soft failure: the walk is marked truncated,
     * reconciliation correctly refuses to run, and the table keeps a partial set for ever —
     * which is why prod held 5,657 CVEs against Upwind's 8,502.
     *
     * Posture is the other grower, now that PASS findings are fetched alongside FAIL:
     * ~29.4k FAIL for Onfinance measured the same day, doubled by PASS.
     *
     * Only as many pages as there is data are ever requested, so a high cap costs nothing on
     * small tenants; it just has to stay above real volume with room to grow.
     */
    static final int FULL_SYNC_MAX_PAGES = 600;
    static final int INVENTORY_MAX_ITEMS = 20000;
    // ~6.8k endpoints measured 2026-07-31. The v2 search caps limit at 100 per page, so this
    // is an ITEM cap (~300 requests worst case) rather than the old page cap.
    static final int APISEC_MAX_ITEMS = 30000;

    private final UpwindApiClient apiClient;
    private final UpwindThreatDetectionRepository detectionRepo;
    private final UpwindVulnerabilityFindingRepository vulnRepo;
    private final UpwindPostureFindingRepository postureRepo;
    private final UpwindSyncStateRepository syncStateRepo;
    private final UpwindInventoryAssetRepository inventoryRepo;
    private final UpwindApiEndpointRepository apiEndpointRepo;
    private final UpwindAccountConnectionRepository connectionRepo;

    public UpwindSyncService(UpwindApiClient apiClient,
                             UpwindThreatDetectionRepository detectionRepo,
                             UpwindVulnerabilityFindingRepository vulnRepo,
                             UpwindPostureFindingRepository postureRepo,
                             UpwindSyncStateRepository syncStateRepo,
                             UpwindInventoryAssetRepository inventoryRepo,
                             UpwindApiEndpointRepository apiEndpointRepo,
                             UpwindAccountConnectionRepository connectionRepo) {
        this.apiClient = apiClient;
        this.detectionRepo = detectionRepo;
        this.vulnRepo = vulnRepo;
        this.postureRepo = postureRepo;
        this.syncStateRepo = syncStateRepo;
        this.inventoryRepo = inventoryRepo;
        this.apiEndpointRepo = apiEndpointRepo;
        this.connectionRepo = connectionRepo;
    }

    public void syncDetections() {
        if (!apiClient.isConfigured()) return;
        try {
            List<JsonNode> items = apiClient.fetchThreatDetections(DETECTION_POLL_MAX_PAGES);
            Instant now = Instant.now();
            for (JsonNode item : items) {
                upsertDetection(item, now);
            }
            recordSuccess("DETECTIONS");
        } catch (Exception e) {
            log.error("[Upwind] Detection sync failed: {}", e.getMessage());
            recordError("DETECTIONS", e);
        }
    }

    /**
     * Full sync with an always-true reconciliation guard. For callers with no lease to lose
     * (tests, single-instance/manual runs).
     */
    public void syncAll() {
        syncAll(() -> true);
    }

    /**
     * @param canReconcile consulted immediately before each mark-resolved pass. The full sync
     *        can outlive its lease TTL, at which point a second ASG instance may already be
     *        running; two overlapping runs archiving against their own start times would each
     *        wipe rows the other just wrote. Archiving is therefore skipped rather than risked.
     */
    public void syncAll(java.util.function.BooleanSupplier canReconcile) {
        if (!apiClient.isConfigured()) return;
        syncDetections();
        Instant runStart = Instant.now();
        try {
            // Streamed, not accumulated: this collection reaches hundreds of thousands of
            // items org-wide, and holding them all was an OutOfMemoryError — which, being an
            // Error, escaped this catch block and killed posture, inventory and endpoints with it.
            UpwindApiClient.StreamResult res = apiClient.streamVulnerabilityFindings(
                    FULL_SYNC_MAX_PAGES, item -> upsertVulnerability(item, Instant.now()));
            // Reconcile only on a COMPLETE fetch: a truncated page walk would archive
            // every finding we simply never re-read. And only while we still hold the lease.
            if (res.count() > 0 && !res.truncated()
                    && mayReconcile(canReconcile, "vulnerabilities")
                    && archiveIsSafe("VULNERABILITIES", res.count(), vulnRepo.countOpen())) {
                vulnRepo.markResolvedNotSyncedSince(runStart);
            }
            log.info("[Upwind] Vulnerability sync upserted {} findings (complete={})",
                    res.count(), !res.truncated());
            if (res.truncated()) {
                recordTruncated("VULNERABILITIES", res.count(), FULL_SYNC_MAX_PAGES);
            } else {
                recordSuccess("VULNERABILITIES");
            }
        } catch (Exception e) {
            log.error("[Upwind] Vulnerability sync failed: {}", e.getMessage());
            recordError("VULNERABILITIES", e);
        }
        syncPosture(canReconcile);
        syncInventory(canReconcile);
        syncApiEndpoints(canReconcile);
    }

    /**
     * Minimum share of the existing live rows a fetch must account for before it is allowed to
     * archive the remainder.
     *
     * This guard is here because its absence caused an outage. Reconciliation had been silently
     * standing down for weeks whenever the lease lapsed mid-sync; adding a lease heartbeat
     * turned it back on, and the first run afterwards archived a table full of rows the fetch
     * did not re-cover, emptying every XamSecure tab on UAT.
     *
     * A "complete" walk is not evidence that the walk saw everything: it only means no page cap
     * was hit. An auth change, a scope change, or an account dropping out of
     * knownCloudAccountIds all produce a small, complete, wrong result. Comparing against what
     * we already hold is the check that catches those, and it fails safe — refusing to archive
     * leaves stale rows, which is recoverable, whereas archiving wrongly is not.
     */
    static final double ARCHIVE_MIN_COVERAGE = 0.5;

    /**
     * Whether a mark-resolved pass may archive, given how much of the existing data this run
     * actually re-covered.
     *
     * @param fetched  rows this run upserted for the domain
     * @param existing live rows already held
     */
    private boolean archiveIsSafe(String domain, long fetched, long existing) {
        if (existing <= 0) return true;                 // nothing to lose
        double coverage = (double) fetched / existing;
        if (coverage < ARCHIVE_MIN_COVERAGE) {
            log.error("[Upwind] {} archiving REFUSED — this run covered {} of {} live rows "
                    + "({}%), below the {}% floor. Stale rows are kept rather than risk wiping "
                    + "data the fetch simply missed.",
                    domain, fetched, existing, Math.round(coverage * 100),
                    Math.round(ARCHIVE_MIN_COVERAGE * 100));
            recordArchiveRefused(domain, fetched, existing);
            return false;
        }
        return true;
    }

    /** Surfaces a refused archive on the Data Freshness panel, not just in the log. */
    private void recordArchiveRefused(String domain, long fetched, long existing) {
        UpwindSyncState s = syncStateRepo.findByDomain(domain).orElseGet(() -> {
            UpwindSyncState n = new UpwindSyncState();
            n.setDomain(domain);
            return n;
        });
        s.setLastSuccessAt(Instant.now());
        s.setLastError("Archiving skipped: this run covered only " + fetched + " of " + existing
                + " live rows. Data is kept but may include rows Upwind no longer reports.");
        s.setLastErrorAt(Instant.now());
        syncStateRepo.save(s);
    }

    /**
     * Whether a destructive mark-resolved pass may run. Logs the refusal, because "findings
     * silently stopped being archived" is otherwise very hard to notice.
     */
    private static boolean mayReconcile(java.util.function.BooleanSupplier canReconcile, String domain) {
        if (canReconcile.getAsBoolean()) return true;
        log.warn("[Upwind] skipping {} mark-resolved — sync lease no longer held, so another "
                + "instance may be mid-run; stale rows will be archived on the next clean run", domain);
        return false;
    }

    /**
     * Statuses fetched for configuration findings, in order.
     *
     * FAIL alone was the old behaviour, and it was an accident: the endpoint DEFAULTS to
     * FAIL when no status is supplied, so PASS findings were never fetched and every
     * per-framework pass count in the Compliance tab was structurally zero. PASS volume is
     * comparable to FAIL (both exceed a thousand rows on this org), so this roughly doubles
     * the table and the sync duration — accepted, because pass/fail ratios are meaningless
     * without it, and a check moving FAIL → PASS is now visible as PASS rather than being
     * silently archived as RESOLVED.
     */
    static final List<String> POSTURE_STATUSES = List.of("FAIL", "PASS");

    /**
     * Full posture sync: one page walk per status, then a single reconciliation pass.
     *
     * Reconciliation is all-or-nothing across statuses. {@code runStart} is captured before
     * the first walk and mark-resolved only runs when EVERY status fetched completely —
     * archiving after a partial walk is exactly the failure that left production showing
     * 100 FAIL against 12,613 RESOLVED.
     */
    public void syncPosture() {
        syncPosture(() -> true);
    }

    public void syncPosture(java.util.function.BooleanSupplier canReconcile) {
        if (!apiClient.isConfigured()) return;
        Instant runStart = Instant.now();
        try {
            int upserted = 0;
            boolean allComplete = true;
            for (String status : POSTURE_STATUSES) {
                // Streamed for the same reason as vulnerabilities: ~29.4k FAIL for one account
                // measured 2026-08-05, doubled by the PASS walk, and growing.
                UpwindApiClient.StreamResult res = apiClient.streamConfigurationFindings(
                        status, FULL_SYNC_MAX_PAGES, item -> upsertPosture(item, Instant.now()));
                upserted += res.count();
                allComplete &= !res.truncated();
                log.info("[Upwind] Posture sync fetched {} {} findings (complete={})",
                        res.count(), status, !res.truncated());
            }
            if (upserted > 0 && allComplete && mayReconcile(canReconcile, "posture")
                    && archiveIsSafe("POSTURE", upserted, postureRepo.countLive())) {
                int resolved = postureRepo.markResolvedNotSyncedSince(runStart);
                log.info("[Upwind] Posture sync archived {} findings no longer reported", resolved);
            }
            log.info("[Upwind] Posture sync upserted {} findings across {} (complete={})",
                    upserted, POSTURE_STATUSES, allComplete);
            if (allComplete) recordSuccess("POSTURE");
            else recordTruncated("POSTURE", upserted, FULL_SYNC_MAX_PAGES);
        } catch (Exception e) {
            log.error("[Upwind] Posture sync failed: {}", e.getMessage());
            recordError("POSTURE", e);
        }
    }

    /**
     * Syncs API-security endpoints per connected account via the documented v2 search. The
     * endpoint payload has no cloud_account_id — tenancy comes from the request filter — so
     * we iterate the known accounts and stamp the id on each row.
     *
     * This exists so the APIs tab reads locally: fetching ~6.8k endpoints inline made the
     * request block ~20s on every 10-minute cache miss.
     */
    public void syncApiEndpoints() {
        syncApiEndpoints(() -> true);
    }

    public void syncApiEndpoints(java.util.function.BooleanSupplier canReconcile) {
        if (!apiClient.isConfigured()) return;
        List<String> accountIds = knownCloudAccountIds();
        if (accountIds.isEmpty()) {
            log.info("[Upwind] API endpoint sync skipped — no known cloud accounts yet");
            return;
        }
        int synced = 0;
        boolean allComplete = true;
        try {
            for (String accountId : accountIds) {
                Instant runStart = Instant.now();
                // Authoritative exposure set. has_internet_ingress is filterable but absent from
                // the response body, so it takes its own walk. A truncated or failed walk means
                // "unknown": we then leave each endpoint's existing flag alone rather than
                // silently marking a whole account not-exposed.
                UpwindApiClient.FetchIdResult exposed =
                        apiClient.searchApiEndpointIds(accountId, "has_internet_ingress",
                                APISEC_MAX_ITEMS);
                java.util.Set<String> exposedIds =
                        (exposed == null || exposed.truncated()) ? null : exposed.ids();
                if (exposedIds == null) {
                    log.warn("[Upwind] internet-ingress walk incomplete for {} — leaving "
                            + "existing exposure flags untouched", accountId);
                }

                UpwindApiClient.FetchResult res =
                        apiClient.searchApiEndpoints(accountId, APISEC_MAX_ITEMS);
                for (JsonNode ep : res.items()) {
                    upsertApiEndpoint(ep, accountId, Instant.now(), exposedIds);
                    synced++;
                }
                if (!res.items().isEmpty() && !res.truncated()
                        && mayReconcile(canReconcile, "API endpoints")
                        && archiveIsSafe("API_ENDPOINTS", res.items().size(),
                                apiEndpointRepo.countActiveByAccount(accountId))) {
                    apiEndpointRepo.markResolvedNotSyncedSince(accountId, runStart);
                }
                allComplete &= !res.truncated();
            }
            log.info("[Upwind] API endpoint sync upserted {} endpoints across {} account(s) (complete={})",
                    synced, accountIds.size(), allComplete);
            if (allComplete) recordSuccess("API_ENDPOINTS");
            else recordTruncated("API_ENDPOINTS", synced, APISEC_MAX_ITEMS);
        } catch (Exception e) {
            log.error("[Upwind] API endpoint sync failed: {}", e.getMessage());
            recordError("API_ENDPOINTS", e);
        }
    }

    /**
     * @param exposedIds ids Upwind reports as internet-exposed, or null when that walk could not
     *        be completed — in which case the stored flag is preserved rather than guessed.
     */
    private void upsertApiEndpoint(JsonNode ep, String accountId, Instant now,
                                   java.util.Set<String> exposedIds) {
        String upwindId = text(ep, "id");
        if (upwindId == null) return;
        UpwindApiEndpoint e = apiEndpointRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindApiEndpoint::new);
        e.setUpwindId(upwindId);
        e.setCloudAccountId(accountId);
        e.setMethod(text(ep, "method"));
        e.setUri(text(ep, "uri"));
        e.setResourceId(text(ep, "resource_id"));
        // Exposure comes from Upwind's own has_internet_ingress verdict, not from the presence
        // of an ingress timestamp. The timestamp is present on essentially every endpoint it has
        // ever seen traffic for, which made the old derivation report 15,324 of 15,325 Xammer
        // endpoints as internet-exposed.
        if (exposedIds != null) {
            e.setInternetExposed(exposedIds.contains(upwindId));
        }
        e.setAuthState(text(ep.path("risk_overview").path("authentication"), "state"));
        StringJoiner domains = new StringJoiner(",");
        ep.path("domains").forEach(d -> domains.add(d.asText()));
        e.setDomains(domains.length() > 0 ? domains.toString() : null);
        e.setFirstSeenTime(instant(ep, "first_seen_time"));
        e.setLastSeenTime(instant(ep, "last_seen_time"));

        // v2-only: sensitive-data findings and observed status codes.
        JsonNode sensitive = ep.path("risk_overview").path("sensitive_data");
        if (sensitive.isArray() && !sensitive.isEmpty()) {
            StringJoiner sj = new StringJoiner(",");
            for (JsonNode s : sensitive) {
                String category = text(s, "category");
                String type = text(s, "type");
                if (category == null && type == null) continue;
                sj.add(category == null ? type : (type == null ? category : category + ":" + type));
            }
            e.setSensitiveData(truncate(sj.toString(), 1024));
            e.setHasSensitiveData(sj.length() > 0);
        } else {
            e.setSensitiveData(null);
            e.setHasSensitiveData(false);
        }
        StringJoiner codes = new StringJoiner(",");
        ep.path("status_codes").forEach(c -> codes.add(c.asText()));
        e.setStatusCodes(codes.length() > 0 ? truncate(codes.toString(), 256) : null);

        e.setStatus("ACTIVE");
        e.setRawPayload(ep.toString());
        e.setSyncedAt(now);
        apiEndpointRepo.save(e);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s == null || s.isEmpty() ? null : s;
        return s.substring(0, max - 3) + "...";
    }

    public void syncInventory() {
        syncInventory(() -> true);
    }

    public void syncInventory(java.util.function.BooleanSupplier canReconcile) {
        if (!apiClient.isConfigured()) return;
        List<String> accountIds = knownCloudAccountIds();
        if (accountIds.isEmpty()) {
            log.info("[Upwind] Inventory sync skipped — no connected cloud accounts registered");
            return;
        }
        Instant runStart = Instant.now();
        try {
            // The catalog search filters on cloud_account_id, so we walk account by account.
            // cloud_provider is NOT a supported catalog filter — it returns 200 with an empty
            // items[] (verified live 2026-08-03), which is how the old per-provider loop would
            // have silently produced an empty inventory after the endpoint switch.
            // Track ids already upserted this run to stay idempotent if pages overlap.
            java.util.Set<String> seenThisRun = new java.util.HashSet<>();
            int syncedCount = 0;
            for (String accountId : accountIds) {
                java.util.List<java.util.Map<String, Object>> conditions = java.util.List.of(
                        java.util.Map.of("field", "cloud_account_id", "operator", "eq",
                                "value", java.util.List.of(accountId)));
                for (JsonNode item : apiClient.searchInventory(conditions, INVENTORY_MAX_ITEMS)) {
                    String upwindId = text(item, "id");
                    if (upwindId != null && !seenThisRun.add(upwindId)) continue;
                    upsertAsset(item, Instant.now());
                    syncedCount++;
                }
            }
            if (syncedCount > 0 && mayReconcile(canReconcile, "inventory")
                    && archiveIsSafe("INVENTORY", syncedCount, inventoryRepo.countActive())) {
                inventoryRepo.markResolvedNotSyncedSince(runStart);
            }
            log.info("[Upwind] Inventory sync upserted {} assets across {} account(s)",
                    syncedCount, accountIds.size());
            recordSuccess("INVENTORY");
        } catch (Exception e) {
            log.error("[Upwind] Inventory sync failed: {}", e.getMessage());
            recordError("INVENTORY", e);
        }
    }

    /**
     * Cloud accounts to sync, as the union of the accounts registered as Upwind connections
     * and the accounts we already hold assets for. The connection table is the bootstrap
     * source: on a fresh database the inventory table is empty, so keying only off previously
     * synced assets would never start.
     */
    private List<String> knownCloudAccountIds() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (UpwindAccountConnection c : connectionRepo.findAll()) {
            if (c.getCloudAccountId() != null && !c.getCloudAccountId().isBlank()) {
                ids.add(c.getCloudAccountId());
            }
        }
        ids.addAll(inventoryRepo.findDistinctCloudAccountIds());
        ids.remove(null);
        return new java.util.ArrayList<>(ids);
    }

    /**
     * Maps an Upwind asset onto our row.
     *
     * Reads BOTH field spellings because two payload shapes reach this method: the inventory
     * catalog search uses {@code category}/{@code sub_category}/{@code resource_type}, while
     * the older flat search and the webhook events use
     * {@code asset_category}/{@code asset_sub_category}/{@code label}.
     */
    private void upsertAsset(JsonNode item, Instant now) {
        String upwindId = text(item, "id");
        if (upwindId == null) return;
        UpwindInventoryAsset a = inventoryRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindInventoryAsset::new);
        a.setUpwindId(upwindId);
        a.setAssetCategory(firstNonBlank(text(item, "category"), text(item, "asset_category")));
        a.setAssetSubCategory(firstNonBlank(text(item, "sub_category"), text(item, "asset_sub_category")));
        a.setLabel(firstNonBlank(text(item, "label"), text(item, "resource_type")));
        a.setCloudProvider(text(item, "cloud_provider"));
        a.setCloudAccountId(text(item, "cloud_account_id"));
        a.setCloudResourceId(text(item, "cloud_resource_id"));
        a.setName(text(item, "name"));
        a.setRegion(text(item, "region"));
        a.setStatus("ACTIVE");

        // Catalog-only risk rollups. Absent objects leave the columns null rather than 0, so
        // "Upwind didn't tell us" stays distinguishable from "Upwind says none".
        JsonNode priv = item.path("high_privilege_risk");
        a.setHasElevatedPrivilege(bool(priv, "has_elevated_privilege"));

        JsonNode rest = item.path("sensitive_data_at_rest");
        a.setSensitivePiiAtRest(bool(rest, "has_pii"));
        a.setSensitivePhiAtRest(bool(rest, "has_phi"));
        a.setSensitivePciAtRest(bool(rest, "has_pci"));

        JsonNode transit = item.path("sensitive_data_in_transit");
        a.setSensitivePiiInTransit(bool(transit, "has_pii"));
        a.setSensitivePhiInTransit(bool(transit, "has_phi"));
        a.setSensitivePciInTransit(bool(transit, "has_pci"));

        JsonNode vuln = item.path("vulnerability_risk");
        a.setVulnCriticalCount(integer(vuln, "critical_count"));
        a.setVulnHighCount(integer(vuln, "high_count"));
        a.setVulnMediumCount(integer(vuln, "medium_count"));
        a.setVulnLowCount(integer(vuln, "low_count"));
        a.setVulnTotalCount(integer(vuln, "total_count"));

        JsonNode det = item.path("detection_risk");
        a.setDetectionCriticalCount(integer(det, "critical_count"));
        a.setDetectionHighCount(integer(det, "high_count"));
        a.setDetectionTotalCount(integer(det, "total_count"));

        JsonNode net = item.path("network_risk");
        a.setInternetExposureType(text(net, "internet_exposure_type"));
        a.setLastInternetCommunication(instant(net, "last_communication_with_internet_time"));

        applyTechnologies(a, item.path("technologies"), now);

        // Provider metadata the catalog returns and we were dropping. Measured 2026-08-11:
        // private_ip_addresses on 404 assets, tags on 477, protected_by on 111, public IPs on 50.
        a.setProtectedBy(truncate(text(item, "protected_by"), 128));
        a.setPrivateIpAddresses(truncate(joinTextArray(item.path("private_ip_addresses")), 512));
        a.setPublicIpAddresses(truncate(joinTextArray(item.path("public_ip_addresses")), 512));
        a.setCloudTags(truncate(joinTextArray(item.path("tags")), 1024));

        a.setRawPayload(item.toString());
        a.setSyncedAt(now);
        inventoryRepo.save(a);
    }

    /** Flattens a JSON string array to a comma-joined value, or null when absent/empty. */
    private static String joinTextArray(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return null;
        StringJoiner sj = new StringJoiner(",");
        for (JsonNode n : node) {
            String v = n.asText(null);
            if (v != null && !v.isBlank()) sj.add(v.trim());
        }
        return sj.length() == 0 ? null : sj.toString();
    }

    /** Column cap for the denormalised technology list (see UpwindInventoryAsset). */
    private static final int TECHNOLOGIES_MAX_LEN = 2048;

    /**
     * Flattens technologies[] to a display list and flags end-of-life/support. An asset
     * running unsupported software is a finding in its own right, so the EOL flag is derived
     * here rather than recomputed on every read.
     */
    private static void applyTechnologies(UpwindInventoryAsset a, JsonNode technologies, Instant now) {
        if (technologies == null || !technologies.isArray() || technologies.isEmpty()) {
            a.setTechnologies(null);
            a.setHasEolTechnology(null);
            return;
        }
        StringJoiner sj = new StringJoiner(", ");
        boolean eol = false;
        for (JsonNode t : technologies) {
            String name = text(t, "name");
            if (name == null) continue;
            String version = text(t, "version");
            Instant endOfLife = instant(t, "end_of_life_time");
            Instant endOfSupport = instant(t, "end_of_support_time");
            boolean expired = (endOfLife != null && endOfLife.isBefore(now))
                    || (endOfSupport != null && endOfSupport.isBefore(now));
            eol |= expired;
            sj.add(name + (version == null ? "" : " " + version) + (expired ? " (EOL)" : ""));
        }
        String flat = sj.toString();
        if (flat.length() > TECHNOLOGIES_MAX_LEN) {
            flat = flat.substring(0, TECHNOLOGIES_MAX_LEN - 3) + "...";
        }
        a.setTechnologies(flat.isEmpty() ? null : flat);
        a.setHasEolTechnology(eol);
    }

    @Transactional
    public void ingestDetection(JsonNode item) {
        upsertDetection(item, Instant.now());
    }

    @Transactional
    public void ingestVulnerability(JsonNode item) {
        upsertVulnerability(item, Instant.now());
    }

    @Transactional
    public void ingestPosture(JsonNode item) {
        upsertPosture(item, Instant.now());
    }

    @Transactional
    public void ingestInventoryAsset(JsonNode item) {
        upsertAsset(item, Instant.now());
    }

    /**
     * Routes a single webhook event to the correct upsert based on a type discriminator
     * in the payload. Upwind webhooks may label the object under any of a few common keys
     * ("type", "event_type", "object_type", "kind", "category"); we match case-insensitively
     * on substrings. Unknown/absent types fall back to a detection (backward compatible with
     * the original detection-only webhook).
     *
     * @return the domain the event was routed to ("detection", "vulnerability", "posture", "inventory")
     */
    @Transactional
    public String ingestEvent(JsonNode item) {
        String type = firstNonBlank(
                text(item, "type"), text(item, "event_type"), text(item, "eventType"),
                text(item, "object_type"), text(item, "objectType"), text(item, "kind"),
                text(item, "category"));
        String t = type == null ? "" : type.toLowerCase();

        if (t.contains("vuln") || t.contains("cve")) {
            upsertVulnerability(item, Instant.now());
            return "vulnerability";
        }
        if (t.contains("posture") || t.contains("config") || t.contains("misconfig") || t.contains("compliance")) {
            upsertPosture(item, Instant.now());
            return "posture";
        }
        if (t.contains("inventory") || t.contains("asset") || t.contains("resource")) {
            upsertAsset(item, Instant.now());
            return "inventory";
        }
        // Default: threat detection (also covers t.contains("detection")/"threat")
        upsertDetection(item, Instant.now());
        return "detection";
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }

    private void upsertDetection(JsonNode item, Instant now) {
        String upwindId = text(item, "id");
        if (upwindId == null) return;
        UpwindThreatDetection d = detectionRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindThreatDetection::new);
        d.setUpwindId(upwindId);
        d.setCategory(text(item, "category"));
        d.setSeverity(text(item, "severity"));
        d.setStatus(text(item, "status"));
        d.setTitle(text(item, "title"));
        JsonNode res = item.path("resource");
        d.setCloudProvider(text(res, "cloud_provider"));
        d.setCloudAccountId(text(res, "cloud_account_id"));
        d.setResourceId(text(res, "id"));
        d.setResourceName(text(res, "name"));
        d.setResourceType(text(res, "type"));
        StringJoiner tactics = new StringJoiner(",");
        for (JsonNode attack : item.path("mitre_attacks")) {
            if (attack.hasNonNull("tactic_name")) tactics.add(attack.get("tactic_name").asText());
        }
        d.setMitreTactics(tactics.length() > 0 ? tactics.toString() : null);
        d.setOccurrenceCount(item.hasNonNull("occurrence_count") ? item.get("occurrence_count").asInt() : null);
        d.setFirstSeenTime(instant(item, "first_seen_time"));
        d.setLastSeenTime(instant(item, "last_seen_time"));
        d.setRawPayload(item.toString());
        d.setSyncedAt(now);
        detectionRepo.save(d);
    }

    private void upsertVulnerability(JsonNode item, Instant now) {
        String upwindId = text(item, "id");
        if (upwindId == null) return;
        UpwindVulnerabilityFinding v = vulnRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindVulnerabilityFinding::new);
        v.setUpwindId(upwindId);
        JsonNode vuln = item.path("vulnerability");
        v.setCveId(text(vuln, "nvd_cve_id"));
        v.setVulnerabilityName(text(vuln, "name"));
        v.setSeverity(text(vuln, "nvd_cvss_v3_severity"));
        v.setCvssScore(text(vuln, "nvd_cvss_v3_score"));
        v.setEpssScore(text(vuln, "epss_score"));
        v.setExploitable(vuln.hasNonNull("exploitable") ? vuln.get("exploitable").asBoolean() : null);
        v.setStatus("OPEN");
        JsonNode res = item.path("resource");
        v.setCloudProvider(text(res, "cloud_provider"));
        v.setCloudAccountId(text(res, "cloud_account_id"));
        v.setRegion(text(res, "region"));
        v.setResourceId(text(res, "id"));
        v.setResourceName(text(res, "name"));
        v.setResourceType(text(res, "type"));
        v.setFirstSeenTime(instant(item, "first_seen_time"));
        v.setLastScanTime(instant(item, "last_scan_time"));

        // Runtime exposure. These decide whether a finding is theoretical or live, and were
        // previously discarded on every finding — leaving the UI with installed counts only.
        JsonNode pkg = item.path("package");
        v.setPackageInUse(bool(pkg, "in_use"));
        JsonNode ingress = res.path("internet_exposure").path("ingress");
        v.setInternetExposed(bool(ingress, "exposed"));
        v.setActiveCommunication(bool(ingress, "active_communication"));

        JsonNode fns = vuln.path("exploitable_functions_in_use");
        if (fns.isArray()) {
            StringJoiner sj = new StringJoiner(",");
            fns.forEach(f -> sj.add(f.asText()));
            v.setExploitableFunctionsInUse(!fns.isEmpty());
            v.setExploitableFunctions(sj.length() > 0 ? truncate(sj.toString(), 1024) : null);
        } else {
            v.setExploitableFunctionsInUse(null);
            v.setExploitableFunctions(null);
        }

        v.setPackageName(text(pkg, "name"));
        v.setPackageVersion(text(pkg, "version"));
        v.setPackageType(text(pkg, "type"));
        v.setPackageFramework(text(pkg, "framework"));

        JsonNode image = item.path("image");
        v.setImageName(text(image, "name"));
        v.setImageTag(text(image, "tag"));
        v.setImageUri(truncate(text(image, "uri"), 512));
        v.setImageOsName(text(image, "os_name"));
        v.setImageOsVersion(text(image, "os_version"));

        // Kubernetes context — the same fields that were unmapped for posture findings.
        v.setClusterId(text(res, "cluster_id"));
        v.setClusterName(text(res, "cluster_name"));
        v.setNamespace(text(res, "namespace"));

        v.setEpssSeverity(text(vuln, "epss_severity"));
        v.setCvssV2Score(text(vuln, "nvd_cvss_v2_score"));
        v.setCvssV2Severity(text(vuln, "nvd_cvss_v2_severity"));
        v.setCvssV4Score(text(vuln, "nvd_cvss_v4_score"));
        v.setCvssV4Severity(text(vuln, "nvd_cvss_v4_severity"));
        StringJoiner sources = new StringJoiner(",");
        vuln.path("known_exploit_sources").forEach(s -> sources.add(s.asText()));
        v.setKnownExploitSources(sources.length() > 0 ? truncate(sources.toString(), 256) : null);
        v.setCveFirstSeenTime(instant(vuln, "cve_first_seen_time"));
        v.setSource(text(item, "source"));

        v.setRawPayload(item.toString());
        v.setSyncedAt(now);
        vulnRepo.save(v);
    }

    private void upsertPosture(JsonNode item, Instant now) {
        String upwindId = text(item, "id");
        if (upwindId == null) return;
        UpwindPostureFinding p = postureRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindPostureFinding::new);
        p.setUpwindId(upwindId);
        p.setSeverity(text(item, "severity"));
        p.setStatus(text(item, "status"));
        p.setTitle(text(item, "title"));
        p.setFrameworkTitle(text(item.path("framework"), "title"));
        p.setCheckTitle(text(item.path("check"), "title"));
        JsonNode res = item.path("resource");
        p.setCloudProvider(text(res, "cloud_provider"));
        p.setCloudAccountId(text(res, "cloud_account_id"));
        p.setRegion(text(res, "region"));
        p.setResourceId(text(res, "id"));
        p.setResourceName(text(res, "name"));
        p.setResourceType(text(res, "type"));
        // Kubernetes context, populated only when the resource is a workload.
        p.setClusterId(text(res, "cluster_id"));
        p.setNamespace(text(res, "namespace"));
        p.setFirstSeenTime(instant(item, "first_seen_time"));
        p.setLastSeenTime(instant(item, "last_seen_time"));
        p.setRawPayload(item.toString());
        p.setSyncedAt(now);
        postureRepo.save(p);
    }

    private void recordSuccess(String domain) {
        UpwindSyncState s = syncStateRepo.findByDomain(domain).orElseGet(() -> {
            UpwindSyncState n = new UpwindSyncState();
            n.setDomain(domain);
            return n;
        });
        s.setLastSuccessAt(Instant.now());
        s.setLastError(null);
        s.setLastErrorAt(null);
        syncStateRepo.save(s);
    }

    /**
     * Records a completed-but-incomplete walk.
     *
     * Truncation used to be a log warning only, so a domain could sit permanently partial with
     * reconciliation refusing to run and nothing on screen saying so. Writing it to sync state
     * puts it on the Data Freshness panel next to the timestamps, where a stale-looking domain
     * is actually investigated.
     */
    private void recordTruncated(String domain, int items, int maxPages) {
        UpwindSyncState s = syncStateRepo.findByDomain(domain).orElseGet(() -> {
            UpwindSyncState n = new UpwindSyncState();
            n.setDomain(domain);
            return n;
        });
        s.setLastSuccessAt(Instant.now());
        s.setLastError("Incomplete: hit the " + maxPages + "-page cap after " + items
                + " items, so archiving was skipped and this domain may be partial.");
        s.setLastErrorAt(Instant.now());
        syncStateRepo.save(s);
    }

    private void recordError(String domain, Exception e) {
        UpwindSyncState s = syncStateRepo.findByDomain(domain).orElseGet(() -> {
            UpwindSyncState n = new UpwindSyncState();
            n.setDomain(domain);
            return n;
        });
        s.setLastError(e.getMessage());
        s.setLastErrorAt(Instant.now());
        syncStateRepo.save(s);
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** null when the field is absent, so "unknown" and "false" stay distinguishable. */
    private static Boolean bool(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asBoolean() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String v = text(node, field);
        try {
            return v == null ? null : Instant.parse(v);
        } catch (Exception e) {
            return null;
        }
    }
}
