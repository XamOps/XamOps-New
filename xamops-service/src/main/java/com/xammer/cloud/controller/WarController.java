package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.war.WarReport;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.war.WarScanDispatcher;
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

    private final CloudAccountRepository cloudAccountRepository;
    private final WarScanDispatcher scanDispatcher;

    public WarController(CloudAccountRepository cloudAccountRepository,
            WarScanDispatcher scanDispatcher) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.scanDispatcher = scanDispatcher;
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

        return scanDispatcher.scan(account, forceRefresh)
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
        scanDispatcher.scan(account, true).exceptionally(ex -> {
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
}
