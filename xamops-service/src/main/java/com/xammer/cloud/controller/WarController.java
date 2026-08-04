package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.war.WarReport;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.war.AwsWarService;
import com.xammer.cloud.service.war.AzureWarService;
import com.xammer.cloud.service.war.GcpWarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * WAR — Workload Attack-surface Review.
 *
 * <p>
 * Answers one question for a cloud account: what in this infrastructure would let an
 * attacker or a crypto-miner get in, and what stops the bill from growing once they
 * do? Evaluates the account against the 86-control AWS security advisory baseline in
 * {@code WarControlCatalog}.
 *
 * <p>
 * Distinct from {@code /api/xamops/security}, which reports individual findings for
 * compliance reporting. WAR groups the same underlying cloud state by attack path and
 * scores the account's overall exposure.
 */
@RestController
@RequestMapping("/api/xamops/war")
public class WarController {

    private static final Logger log = LoggerFactory.getLogger(WarController.class);

    /**
     * A full scan makes hundreds of API calls per region. The timeout sits just under
     * the gateway's own so a slow scan returns 503 with a retry hint rather than a
     * dropped connection.
     */
    private static final int SCAN_TIMEOUT_SECONDS = 110;

    /**
     * Regions scanned when the account has no discovered region list yet. Mirrors
     * SecurityController.ComplianceRegions so both pages describe the same footprint.
     */
    private static final List<String> FALLBACK_REGIONS = List.of(
            "ap-south-1", "us-east-1", "ap-southeast-1");

    private final AwsWarService awsWarService;
    private final GcpWarService gcpWarService;
    private final AzureWarService azureWarService;
    private final CloudAccountRepository cloudAccountRepository;
    private final CloudListService cloudListService;

    public WarController(AwsWarService awsWarService,
            GcpWarService gcpWarService,
            AzureWarService azureWarService,
            CloudAccountRepository cloudAccountRepository,
            CloudListService cloudListService) {
        this.awsWarService = awsWarService;
        this.gcpWarService = gcpWarService;
        this.azureWarService = azureWarService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cloudListService = cloudListService;
    }

    @GetMapping("/report")
    public CompletableFuture<ResponseEntity<WarReport>> getReport(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[WAR] /report accountId={} forceRefresh={}", accountId, forceRefresh);

        CloudAccount account = resolve(accountId);
        if (account == null) {
            return CompletableFuture.completedFuture(ResponseEntity.notFound().build());
        }

        return scan(account, forceRefresh)
                .orTimeout(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[WAR] scan timed out for account {}", accountId);
                        // The scan keeps running and populates the cache, so a retry
                        // shortly after usually returns the completed report.
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
                    }
                    log.error("[WAR] scan failed for account {}", accountId, cause);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    /**
     * Dispatches to the provider's scanner.
     *
     * <p>
     * AWS needs its active-region list resolved first because most of its resources are
     * regional. GCP and Azure enumerate subscription- or project-wide in one call, so the
     * provider id is all their scanners need.
     */
    private CompletableFuture<WarReport> scan(CloudAccount account, boolean forceRefresh) {
        String provider = account.getProvider() == null ? "AWS" : account.getProvider();
        return switch (provider) {
            case "GCP" -> gcpWarService.getReport(account, forceRefresh);
            case "Azure" -> azureWarService.getReport(account, forceRefresh);
            case "AWS" -> resolveRegions(account, forceRefresh)
                    .thenCompose(regions -> awsWarService.getReport(account, regions, forceRefresh));
            default -> CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "WAR does not have a baseline for provider " + provider + " yet"));
        };
    }

    /**
     * Kicks off a fresh scan without waiting for it. Lets the UI show "scanning" and
     * poll {@code /report} rather than holding a request open for two minutes.
     */
    @PostMapping("/scan")
    public ResponseEntity<Void> triggerScan(@RequestParam String accountId) {
        CloudAccount account = resolve(accountId);
        if (account == null) {
            return ResponseEntity.notFound().build();
        }
        log.info("[WAR] background scan requested for account {}", accountId);
        scan(account, true).exceptionally(ex -> {
            log.error("[WAR] background scan failed for account {}", accountId, ex);
            return null;
        });
        return ResponseEntity.accepted().build();
    }

    /**
     * Resolves the account by whichever provider id matches.
     *
     * <p>
     * The frontend sends one {@code accountId} regardless of provider — for GCP that is
     * the project id, which lives in a different column than the AWS account number.
     */
    private CloudAccount resolve(String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (!accounts.isEmpty()) {
            return accounts.get(0);
        }
        Optional<CloudAccount> gcpAccount = cloudAccountRepository.findByGcpProjectId(accountId);
        if (gcpAccount.isPresent()) {
            return gcpAccount.get();
        }
        Optional<CloudAccount> azureAccount =
                cloudAccountRepository.findByAzureSubscriptionId(accountId);
        if (azureAccount.isPresent()) {
            return azureAccount.get();
        }
        log.warn("[WAR] no cloud account registered for accountId {}", accountId);
        return null;
    }

    private CompletableFuture<List<String>> resolveRegions(CloudAccount account, boolean forceRefresh) {
        return cloudListService.getRegionStatusForAccount(account, forceRefresh)
                .thenApply(statuses -> {
                    List<String> regions = statuses.stream()
                            .map(s -> s.getRegionId())
                            .filter(id -> id != null && !id.isBlank())
                            .distinct()
                            .toList();
                    return regions.isEmpty() ? FALLBACK_REGIONS : regions;
                })
                .exceptionally(ex -> {
                    log.warn("[WAR] region discovery failed for account {}, using fallback list: {}",
                            account.getAwsAccountId(), ex.getMessage());
                    return FALLBACK_REGIONS;
                });
    }
}
