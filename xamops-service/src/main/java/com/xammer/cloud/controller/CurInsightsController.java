package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.cur.CurAdvancedMetricsService;
import com.xammer.cloud.service.cur.CurSourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cost insights that only a CUR can answer.
 *
 * <p>Every endpoint returns {@code 409} with an explanation when the account has no
 * usable CUR, rather than an empty body. These are not features that degrade to
 * Cost Explorer — CE cannot express any of them — so a client showing an empty
 * chart would be misrepresenting "not available here" as "nothing to show".
 */
@RestController
@RequestMapping("/api/xamops/cur-insights")
public class CurInsightsController {

    private static final Logger logger = LoggerFactory.getLogger(CurInsightsController.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final CurSourceResolver curSourceResolver;
    private final CurAdvancedMetricsService metricsService;

    public CurInsightsController(CloudAccountRepository cloudAccountRepository,
                                 CurSourceResolver curSourceResolver,
                                 CurAdvancedMetricsService metricsService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.curSourceResolver = curSourceResolver;
        this.metricsService = metricsService;
    }

    /** Effective savings rate: paid vs public on-demand, over a month. */
    @GetMapping("/savings-rate/{accountId}")
    public ResponseEntity<?> savingsRate(@PathVariable String accountId,
                                         @RequestParam(required = false) String period) {
        return withSource(accountId, period, (source, account, start, end) ->
                metricsService.getEffectiveSavingsRate(source, account, start, end));
    }

    /**
     * Charges by service, as the AWS bill lists them: description, usage quantity, amount.
     *
     * <p>Defaults to the current month. Unlike the period comparisons this is meaningful
     * mid-month — it describes what has been billed so far rather than comparing two spans
     * of different length.
     */
    @GetMapping("/charges-by-service/{accountId}")
    public ResponseEntity<?> chargesByService(@PathVariable String accountId,
                                              @RequestParam(required = false) String period) {
        return withSource(accountId, period, (source, account, start, end) ->
                metricsService.getChargesByService(source, account, start, end));
    }

    /**
     * Data transfer and network costs, grouped by what drives them.
     *
     * <p>Defaults to the current month — a breakdown of what has been billed, so it is
     * meaningful mid-month.
     */
    @GetMapping("/data-transfer/{accountId}")
    public ResponseEntity<?> dataTransfer(@PathVariable String accountId,
                                          @RequestParam(required = false) String period) {
        return withSource(accountId, period, (source, account, start, end) ->
                metricsService.getDataTransferCosts(source, account, start, end));
    }

    /** Charge type split — usage vs tax vs credits vs refunds vs fees. */
    @GetMapping("/charge-types/{accountId}")
    public ResponseEntity<?> chargeTypes(@PathVariable String accountId,
                                         @RequestParam(required = false) String period) {
        return withSource(accountId, period, (source, account, start, end) ->
                metricsService.getChargeTypeBreakdown(source, account, start, end));
    }

    /** Reservation and Savings Plan spend that went unused. */
    @GetMapping("/unused-commitment/{accountId}")
    public ResponseEntity<?> unusedCommitment(@PathVariable String accountId,
                                              @RequestParam(required = false) String period) {
        return withSource(accountId, period, (source, account, start, end) ->
                metricsService.getUnusedCommitment(source, account, start, end));
    }

    /** Per-namespace EKS cost. Empty unless the report carries split cost allocation data. */
    @GetMapping("/container-costs/{accountId}")
    public ResponseEntity<?> containerCosts(@PathVariable String accountId,
                                            @RequestParam(required = false) String period) {
        return withSource(accountId, period, (source, account, start, end) -> {
            List<CurAdvancedMetricsService.ContainerCost> costs =
                    metricsService.getContainerCosts(source, account, start, end);
            Map<String, Object> body = new HashMap<>();
            body.put("costs", costs);
            // Tells the client the difference between "no container spend" and "this
            // report was never created with SPLIT_COST_ALLOCATION_DATA", which look
            // identical from an empty list.
            body.put("splitCostDataEnabled", Boolean.TRUE.equals(source.getSplitCostDataEnabled()));
            return body;
        });
    }

    /**
     * Price vs volume decomposition against the preceding month.
     *
     * <p>Both periods must be inside the report's coverage, so the resolver is asked
     * about the earlier one — a report that does not reach back far enough cannot
     * produce a comparison, and a silently one-sided one would read as 100% growth.
     */
    @GetMapping("/price-vs-volume/{accountId}")
    public ResponseEntity<?> priceVsVolume(@PathVariable String accountId,
                                           @RequestParam(required = false) String period) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CloudAccount account = accounts.get(0);

        YearMonth current = period != null ? YearMonth.parse(period) : YearMonth.now();
        YearMonth prior = current.minusMonths(1);

        Optional<CurSource> source = curSourceResolver.resolve(
                account, prior.atDay(1), current.atEndOfMonth());
        if (source.isEmpty()) {
            return noCurSource(accountId);
        }

        try {
            return ResponseEntity.ok(metricsService.getPriceVsVolume(source.get(), account,
                    current.atDay(1), endOf(current),
                    prior.atDay(1), prior.atEndOfMonth()));
        } catch (Exception e) {
            return queryFailed(accountId, e);
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface SourceQuery {
        Object run(CurSource source, CloudAccount account, LocalDate start, LocalDate end);
    }

    private ResponseEntity<?> withSource(String accountId, String period, SourceQuery query) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CloudAccount account = accounts.get(0);

        YearMonth target = period != null ? YearMonth.parse(period) : YearMonth.now();
        LocalDate start = target.atDay(1);
        LocalDate end = endOf(target);

        Optional<CurSource> source = curSourceResolver.resolve(account, start, end);
        if (source.isEmpty()) {
            return noCurSource(accountId);
        }

        try {
            return ResponseEntity.ok(query.run(source.get(), account, start, end));
        } catch (Exception e) {
            return queryFailed(accountId, e);
        }
    }

    /**
     * The full month, including the current one — deliberately not capped at today.
     *
     * <p>Capping looks right ("later days have no data yet") and is wrong. Not every line
     * item is dated on the day the charge is incurred: reservation and Savings Plan fees,
     * support and subscriptions carry usage dates spanning the whole billing period from
     * the day it opens, and they are already on the bill.
     *
     * <p>Measured on a live report for September: capping at today gave $1,107 against
     * $2,367 for the same account and month — the $1,260 difference being period-spanning
     * rows, not charges that had yet to happen. It also made this endpoint disagree with
     * the cost pages, which reach the same figure through
     * {@code CostService.normalizeEndDate} stretching to end-of-month.
     */
    private LocalDate endOf(YearMonth month) {
        return month.atEndOfMonth();
    }

    private ResponseEntity<Map<String, String>> noCurSource(String accountId) {
        return ResponseEntity.status(409).body(Map.of(
                "error", "No Cost and Usage Report is available for account " + accountId,
                "detail", "These insights are derived from CUR columns that the Cost Explorer API"
                        + " does not expose, so there is no fallback. Connect a CUR for this account."));
    }

    private ResponseEntity<Map<String, String>> queryFailed(String accountId, Exception e) {
        logger.warn("[CUR] Insight query failed for account {}: {}", accountId, e.getMessage());
        return ResponseEntity.status(502).body(Map.of(
                "error", "Could not read the Cost and Usage Report",
                "detail", String.valueOf(e.getMessage())));
    }
}
