package com.xammer.cloud.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.security.IdentityInventoryResponse;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.security.DetectorCoverageService;
import com.xammer.cloud.service.security.SecurityFindingProjectionService;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.ExcelExportService;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.SecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

// Regions scanned on every compliance run — matches SecurityScheduler.AWS_REGIONS
// IAM, S3, CloudTrail are global; the rest are per-region services.
// Extend this list when customers expand to additional regions.
final class ComplianceRegions {
    static final List<String> AWS = List.of(
            "ap-south-1",    // Mumbai
            "ap-south-2",    // Hyderabad
            "us-east-1",     // N. Virginia (global-service endpoints)
            "ap-southeast-1" // Singapore
    );
    private ComplianceRegions() {}
}

@RestController
@RequestMapping("/api/xamops/security")
public class SecurityController {

    private static final Logger logger = LoggerFactory.getLogger(SecurityController.class);

    private final SecurityService securityService;
    private final CloudAccountRepository cloudAccountRepository;
    private final CloudListService cloudListService;
    private final DashboardDataService dashboardDataService;
    private final ExcelExportService excelExportService;
    private final ProwlerService prowlerService;
    private final SecurityFindingProjectionService liveFindings;
    private final DetectorCoverageService detectorCoverage;

    public SecurityController(SecurityService securityService,
            CloudAccountRepository cloudAccountRepository,
            CloudListService cloudListService,
            DashboardDataService dashboardDataService,
            ExcelExportService excelExportService,
            ProwlerService prowlerService,
            SecurityFindingProjectionService liveFindings,
            DetectorCoverageService detectorCoverage) {
        this.detectorCoverage = detectorCoverage;
        this.securityService = securityService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cloudListService = cloudListService;
        this.dashboardDataService = dashboardDataService;
        this.excelExportService = excelExportService;
        this.prowlerService = prowlerService;
        this.liveFindings = liveFindings;
    }

    /**
     * Findings that arrived over the event stream, straight from the table.
     *
     * <p>
     * Separate from {@code /findings} because it answers a different question. That endpoint
     * returns the full posture picture, most of which comes from scans; this one returns only
     * what changed recently, which is what makes the pipeline observable — create something
     * insecure in AWS, call this, and it should be here within seconds.
     */
    @GetMapping("/live-findings")
    public ResponseEntity<Map<String, Object>> liveFindings(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "0") int withinMinutes) {

        Map<String, Object> body = new HashMap<>();
        body.put("accountId", accountId);
        body.put("enabled", liveFindings.isEnabled());

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            body.put("findings", List.of());
            body.put("hint", "No cloud_account row for " + accountId
                    + ". Findings are keyed on the connection, not the AWS account id.");
            return ResponseEntity.ok(body);
        }
        Long cloudAccountId = accounts.get(0).getId();

        List<CloudSecurityFinding> findings = withinMinutes > 0
                ? liveFindings.recent(cloudAccountId, withinMinutes)
                : liveFindings.active(cloudAccountId);

        body.put("findings", findings);
        body.put("countsBySeverity", liveFindings.countsBySeverity(cloudAccountId));
        body.put("activeTotal", liveFindings.activeCount(cloudAccountId));
        if (!liveFindings.isEnabled()) {
            body.put("hint", "xamops.security.live-findings.enabled is false, so findings are "
                    + "being ingested but not served. Set it true to surface them.");
        }
        // Included on the same response so the page can render the "your stack is missing N
        // detectors" banner without a second round trip — and, more importantly, so an empty
        // findings list is never shown without saying whether anything was actually watching.
        body.put("coverage", detectorCoverage.coverageFor(cloudAccountId));
        return ResponseEntity.ok(body);
    }

    /**
     * IAM identities for the Identity &amp; Access tab, without the rest of the dashboard.
     *
     * <p>
     * The tab previously called {@code /dashboard/data} and used one field of the response.
     * That endpoint joins ~18 AWS collectors and blocks on all of them, so the identity view
     * inherited the latency of Cost Explorer, waste detection and reservation analysis; past
     * the gateway's read timeout the page received nginx's HTML 504 and rendered zero
     * identities. This path runs the IAM scan alone and nothing else.
     *
     * <p>
     * Returns {@code CompletableFuture} deliberately. Spring applies
     * {@code spring.mvc.async.request-timeout} to async handlers only, so a scan that does run
     * long fails as a clean JSON 503 from the app rather than as an HTML error page from the
     * proxy — which is what made this look like a frontend bug.
     *
     * <p>
     * Only AWS gets the fast path today: GCP and Azure assemble {@code iamDetails} inside their
     * own dashboard builders, and reusing those would mean refactoring them. They fall through
     * to the aggregate, exactly as before, so this cannot regress them.
     */
    @GetMapping("/identities")
    public CompletableFuture<ResponseEntity<IdentityInventoryResponse>> identities(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        CloudAccount account;
        try {
            account = resolveOwnedAccount(accountId, userDetails);
        } catch (SecurityException e) {
            logger.warn("Rejected identity request for account {} — not owned by client {}",
                    accountId, userDetails == null ? "?" : userDetails.getClientId());
            return CompletableFuture.completedFuture(ResponseEntity.status(403).build());
        } catch (IllegalArgumentException e) {
            logger.warn("Identity request for unknown account {}", accountId);
            return CompletableFuture.completedFuture(ResponseEntity.notFound().build());
        }

        String provider = account.getProvider() == null ? "AWS" : account.getProvider().toUpperCase();

        if ("AWS".equals(provider)) {
            return dashboardDataService.getIamDetails(account, forceRefresh)
                    .thenApply(details -> ResponseEntity.ok(new IdentityInventoryResponse(
                            provider, accountId, account.getAccountName(), details)))
                    .exceptionally(ex -> {
                        logger.error("IAM identity scan failed for account {}", accountId, ex);
                        return ResponseEntity.status(502).build();
                    });
        }

        // GCP and Azure: unchanged cost, unchanged behaviour. Pulled from the aggregate so this
        // endpoint is the single path the frontend needs, rather than the client having to guess
        // the provider before it knows which URL to call.
        return CompletableFuture.supplyAsync(() -> {
            try {
                DashboardData data = dashboardDataService.getDashboardData(accountId, forceRefresh, userDetails);
                DashboardData.IamDetail details = data.getSelectedAccount() == null
                        ? null
                        : data.getSelectedAccount().getIamDetails();
                String name = data.getSelectedAccount() == null
                        ? account.getAccountName()
                        : data.getSelectedAccount().getName();
                return ResponseEntity.ok(new IdentityInventoryResponse(provider, accountId, name, details));
            } catch (Exception ex) {
                logger.error("Identity fetch failed for {} account {}", provider, accountId, ex);
                return ResponseEntity.status(502).<IdentityInventoryResponse>build();
            }
        });
    }

    /**
     * Resolves an account and confirms the caller's tenant owns it.
     *
     * <p>
     * The ownership check is here because this endpoint returns who holds administrator access
     * in a cloud account, which is the last thing that should be readable by account id alone.
     * Note that the other read endpoints on this controller resolve by id without it — worth
     * closing separately, but not silently widened by adding another one.
     *
     * @throws IllegalArgumentException when no account matches
     * @throws SecurityException        when the account belongs to a different client
     */
    private CloudAccount resolveOwnedAccount(String accountId, ClientUserDetails userDetails) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        String id = accountId.contains(",") ? accountId.split(",")[0].trim() : accountId.trim();

        CloudAccount account = cloudAccountRepository.findByProviderAccountId(id)
                .orElseGet(() -> {
                    List<CloudAccount> matches = cloudAccountRepository.findByAwsAccountId(id);
                    if (matches.isEmpty()) {
                        throw new IllegalArgumentException("No account found for " + id);
                    }
                    return matches.get(0);
                });

        Long owner = account.getClient() == null ? null : account.getClient().getId();
        Long caller = userDetails == null ? null : userDetails.getClientId();
        if (owner == null || caller == null || !owner.equals(caller)) {
            throw new SecurityException("Account " + id + " is not owned by the calling client");
        }
        return account;
    }

    /**
     * Which detectors are running for this account.
     *
     * <p>
     * Its own endpoint as well as being embedded above, because the nudge belongs in more than one
     * place — the onboarding checklist and the account settings page both need it without pulling
     * the whole findings list.
     */
    @GetMapping("/detector-coverage")
    public ResponseEntity<Map<String, Object>> detectorCoverage(@RequestParam String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "ACCOUNT_NOT_FOUND",
                    "message", "No connection found for " + accountId + "."));
        }
        return ResponseEntity.ok(detectorCoverage.coverageFor(accounts.get(0).getId()));
    }

    @GetMapping("/findings")
    public CompletableFuture<ResponseEntity<List<DashboardData.SecurityFinding>>> getSecurityFindings(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("Fetching security findings for accountId: {}, forceRefresh: {}", accountId, forceRefresh);

        String cacheKey = "security-" + accountId;

        // Resolved up front because the live-findings merge below needs the cloud_account row
        // on both the cached and the fresh path, and the plane is keyed on that row rather
        // than on the 12-digit AWS id.
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        Long cloudAccountId = accounts.isEmpty() ? null : accounts.get(0).getId();

        if (!forceRefresh) {
            // ⭐ NEW: Check database cache first
            List<DashboardData.SecurityFinding> cachedFindings = (List<DashboardData.SecurityFinding>) dashboardDataService
                    .getSecuritySnapshot(
                            cacheKey, new TypeReference<List<DashboardData.SecurityFinding>>() {
                            });

            if (cachedFindings != null) {
                logger.info("Serving security findings from database cache for account {}", accountId);
                // Merged rather than returned as-is. This snapshot is a cached_data row with
                // no expiry, so without the merge an event-driven finding would stay invisible
                // until something happened to rewrite the snapshot — and the Redis tier in
                // front of it has a 1440-minute TTL, so "something" could be a day away.
                return CompletableFuture.completedFuture(ResponseEntity.ok(
                        liveFindings.mergeInto(cachedFindings, cloudAccountId)));
            }
        }

        if (accounts.isEmpty()) {
            logger.warn("No account found for accountId: {}", accountId);
            return CompletableFuture.completedFuture(
                    ResponseEntity.ok(Collections.emptyList()));
        }

        CloudAccount account = accounts.get(0);

        // Fallback: Fetch fresh data
        logger.info("Fetching fresh security findings for account {}", accountId);
        return cloudListService.getRegionStatusForAccount(account, forceRefresh)
                .thenCompose(activeRegions -> {
                    logger.info("Found {} active regions for account {}",
                            activeRegions.size(), accountId);
                    return securityService.getComprehensiveSecurityFindings(
                            account, activeRegions, forceRefresh);
                })
                .thenApply(findings -> {
                    List<DashboardData.SecurityFinding> merged =
                            liveFindings.mergeInto(findings, cloudAccountId);
                    logger.info("Returning {} security findings for account {} ({} from the live "
                            + "event stream)", merged.size(), accountId, merged.size() - findings.size());
                    return ResponseEntity.ok(merged);
                })
                .exceptionally(ex -> {
                    logger.error("Error fetching security findings for account {}",
                            accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/export")
    public CompletableFuture<ResponseEntity<byte[]>> exportFindingsToExcel(@RequestParam String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found: " + accountId);
        }
        CloudAccount account = accounts.get(0);

        return cloudListService.getRegionStatusForAccount(account, true)
                .thenCompose((List<DashboardData.RegionStatus> activeRegions) -> securityService
                        .getComprehensiveSecurityFindings(account, activeRegions, true))
                .thenApply(findings -> {
                    ByteArrayInputStream in = excelExportService.exportSecurityFindingsToExcel(findings);
                    HttpHeaders headers = new HttpHeaders();
                    headers.add("Content-Disposition", "attachment; filename=security-findings.xlsx");

                    return ResponseEntity
                            .ok()
                            .headers(headers)
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(in.readAllBytes());
                })
                .exceptionally(ex -> {
                    logger.error("Error exporting security findings for account {}", accountId, ex);
                    return ResponseEntity.status(500).build();
                });
    }

    // ✅ Trigger a full compliance scan with all frameworks
    @PostMapping("/prowler/scan")
    public ResponseEntity<Map<String, Object>> triggerComplianceScan(@RequestParam String accountId) {
        logger.info("Manual compliance scan triggered for accountId: {}", accountId);

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "Account not found: " + accountId));
        }

        // Check if a scan is already running
        Map<String, Object> currentStatus = prowlerService.getScanStatus(accountId);
        if ("RUNNING".equals(currentStatus.get("status"))) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_RUNNING",
                    "message", "A scan is already in progress for this account."));
        }

        // Shared with the weekly scheduler via ProwlerFrameworks so the two paths cannot drift.
        // Core service groups only — running all 17 simultaneously causes OOM on constrained hosts.
        prowlerService.triggerScanAsync(accountId, ComplianceRegions.AWS,
                com.xammer.cloud.service.security.ProwlerFrameworks.AWS_SERVICES,
                com.xammer.cloud.service.security.ProwlerFrameworks.AWS);

        return ResponseEntity.ok(Map.of(
                "status", "STARTED",
                "message", "Compliance scan triggered successfully. Poll /prowler/status for progress."));
    }

    // ✅ Endpoint for Polling Status
    @GetMapping("/prowler/status")
    public ResponseEntity<Map<String, Object>> getProwlerStatus(@RequestParam String accountId) {
        return ResponseEntity.ok(prowlerService.getScanStatus(accountId));
    }

    // ✅ Raw Prowler findings in OCSF format — consumed by ComplianceOps UI
    @GetMapping("/prowler/findings")
    public ResponseEntity<List<?>> getProwlerFindings(@RequestParam String accountId) {
        return ResponseEntity.ok(prowlerService.getCachedFindings(accountId));
    }
}