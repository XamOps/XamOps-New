package com.xammer.billops.controller;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.repository.BillOpsCachedDataRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.service.CurIngestionService;
import com.xammer.billops.service.CurSnapshotScheduler;
import com.xammer.cloud.domain.CachedData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Operational endpoints for the CUR reconciliation pipeline.
 *
 * The reconcile endpoint exists because switching invoicing to a new cost source
 * should not be a leap of faith: it reports the CUR figure, the Cost Explorer
 * figure and the delta for the same account and month, so a month can be verified
 * against the real AWS bill before anyone trusts it. Admin-only, since these expose
 * cross-account billing totals.
 */
@RestController
@RequestMapping("/api/billops/cur")
@CrossOrigin(origins = { "http://localhost:5173", "http://127.0.0.1:5500", "https://uat.xamops.com",
        "https://live.xamops.com", "https://prep.xamops.com" }, allowCredentials = "true")
public class CurAdminController {

    private static final Logger logger = LoggerFactory.getLogger(CurAdminController.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final CurIngestionService curIngestionService;
    private final CurSnapshotScheduler curSnapshotScheduler;
    private final BillOpsCachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    public CurAdminController(CloudAccountRepository cloudAccountRepository,
                             CurIngestionService curIngestionService,
                             CurSnapshotScheduler curSnapshotScheduler,
                             BillOpsCachedDataRepository cachedDataRepository,
                             ObjectMapper objectMapper) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.curIngestionService = curIngestionService;
        this.curSnapshotScheduler = curSnapshotScheduler;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Reports whether CUR is configured and which months have been ingested.
     * The first thing to check when an invoice shows an unexpected source label.
     */
    @GetMapping("/status")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<Map<String, Object>> status(@RequestParam String accountId,
                                                      @RequestParam(defaultValue = "6") int months) {
        CloudAccount account = resolve(accountId);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("curConfigured", curIngestionService.isConfigured(account));
        out.put("curS3Bucket", account.getCurS3Bucket());
        out.put("curReportPath", account.getCurReportPath());

        Map<String, Object> perMonth = new LinkedHashMap<>();
        YearMonth current = YearMonth.now();
        for (int i = 0; i < Math.max(1, months); i++) {
            YearMonth ym = current.minusMonths(i);
            Map<String, Object> m = new LinkedHashMap<>();
            boolean ingested = curIngestionService.hasCurData(accountId, ym);
            m.put("ingested", ingested);
            m.put("closedMonth", ym.isBefore(current));
            // An open month is intentionally never CUR-authoritative — surfaced here so
            // "not ingested" is not mistaken for a fault.
            m.put("usedForInvoicing", ingested && ym.isBefore(current));
            if (ingested) {
                readState(accountId, ym).ifPresent(state -> {
                    m.put("total", state.getTotal());
                    m.put("rowCount", state.getRowCount());
                    m.put("assemblyId", state.getAssemblyId());
                    m.put("ingestedAt", state.getIngestedAt());
                });
            }
            perMonth.put(ym.toString(), m);
        }
        out.put("months", perMonth);
        return ResponseEntity.ok(out);
    }

    /**
     * Compares the reconciled CUR total against the Cost Explorer total for the same
     * month. Run this against a month whose real invoice is known before trusting CUR
     * for a customer — a large delta usually means a partial export rather than a
     * parsing fault, and the row count tells the two apart.
     */
    @GetMapping("/reconcile")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<Map<String, Object>> reconcile(@RequestParam String accountId,
                                                          @RequestParam int year,
                                                          @RequestParam int month) {
        YearMonth ym = YearMonth.of(year, month);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("billingPeriod", ym.toString());

        Optional<Double> curTotal = curIngestionService.readTotal(accountId, ym);
        // Compared against the CE snapshot xamops-service already writes, so this
        // costs no Cost Explorer API calls.
        Optional<Double> ceTotal = readCeSnapshotTotal(accountId, ym);

        out.put("curTotal", curTotal.orElse(null));
        out.put("costExplorerTotal", ceTotal.orElse(null));

        if (curTotal.isPresent() && ceTotal.isPresent()) {
            double delta = curTotal.get() - ceTotal.get();
            out.put("delta", round(delta));
            double base = Math.abs(ceTotal.get());
            out.put("deltaPercent", base > 0 ? round(delta / base * 100.0) : null);
            out.put("note", "CUR is the invoice-accurate figure; a delta here is expected "
                    + "wherever Cost Explorer omits or redistributes tax, credits, refunds or data transfer.");
        } else if (curTotal.isEmpty()) {
            out.put("note", "No CUR data for this period — invoicing falls back to Cost Explorer.");
        } else {
            out.put("note", "No Cost Explorer snapshot for this period to compare against.");
        }

        readState(accountId, ym).ifPresent(state -> out.put("curRowCount", state.getRowCount()));
        return ResponseEntity.ok(out);
    }

    /** Forces a re-ingest of one month, bypassing the assemblyId short-circuit. */
    @PostMapping("/ingest")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<Map<String, Object>> ingest(@RequestParam String accountId,
                                                       @RequestParam int year,
                                                       @RequestParam int month,
                                                       @RequestParam(defaultValue = "true") boolean force) {
        CloudAccount account = resolve(accountId);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        YearMonth ym = YearMonth.of(year, month);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("billingPeriod", ym.toString());

        return curIngestionService.ingestMonth(account, ym, force)
                .map(agg -> {
                    out.put("status", "ingested");
                    out.put("total", agg.getTotal());
                    out.put("rowCount", agg.getRowCount());
                    out.put("services", agg.getSvcRegion().size());
                    out.put("usageTypes", agg.getSvcUsage().size());
                    return ResponseEntity.ok(out);
                })
                .orElseGet(() -> {
                    out.put("status", "no data ingested");
                    out.put("reason", curIngestionService.isConfigured(account)
                            ? "no export delivered for this period yet, or nothing changed"
                            : "no CUR export configured for this account");
                    return ResponseEntity.ok(out);
                });
    }

    /** Backfills the configured window for one account, e.g. right after onboarding. */
    @PostMapping("/backfill")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<Map<String, String>> backfill(@RequestParam String accountId) {
        CloudAccount account = resolve(accountId);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        logger.info("[CUR] Manual backfill requested for account {}", accountId);
        return ResponseEntity.ok(curSnapshotScheduler.backfillAccount(account));
    }

    /**
     * Re-probes the conventional CUR bucket and records it if present.
     * For an already-connected customer who has just re-run their onboarding stack
     * and does not want to wait for the next sweep.
     */
    @PostMapping("/detect")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<Map<String, Object>> detect(@RequestParam String accountId) {
        CloudAccount account = resolve(accountId);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);

        return curIngestionService.detectCurConfig(account)
                .map(config -> {
                    account.setCurS3Bucket(config[0]);
                    account.setCurReportPath(config[1]);
                    cloudAccountRepository.save(account);
                    out.put("detected", true);
                    out.put("curS3Bucket", config[0]);
                    out.put("curReportPath", config[1]);
                    return ResponseEntity.ok(out);
                })
                .orElseGet(() -> {
                    out.put("detected", false);
                    out.put("reason", "bucket " + CurIngestionService.conventionalBucket(accountId)
                            + " is absent or not readable by the onboarding role");
                    return ResponseEntity.ok(out);
                });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private CloudAccount resolve(String accountId) {
        List<CloudAccount> found = cloudAccountRepository.findByAwsAccountIdIn(List.of(accountId));
        return found.isEmpty() ? null : found.get(0);
    }

    private Optional<CurIngestionService.CurIngestState> readState(String accountId, YearMonth ym) {
        try {
            return cachedDataRepository.findById(CurIngestionService.keyState(accountId, ym))
                    .map(row -> {
                        try {
                            return objectMapper.readValue(row.getJsonData(),
                                    CurIngestionService.CurIngestState.class);
                        } catch (Exception e) {
                            return null;
                        }
                    });
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Reads the CE monthly total snapshot written by xamops-service. */
    private Optional<Double> readCeSnapshotTotal(String accountId, YearMonth ym) {
        try {
            Optional<CachedData> row = cachedDataRepository.findById(
                    "AWS_MONTHLY_TOTAL::" + accountId + "::" + ym);
            if (row.isEmpty()) return Optional.empty();
            return Optional.ofNullable(objectMapper.readValue(row.get().getJsonData(), Double.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static double round(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
