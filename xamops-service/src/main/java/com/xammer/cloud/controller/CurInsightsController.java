package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.cur.CurAdvancedMetricsService;
import com.xammer.cloud.service.cur.CurInsightCache;
import com.xammer.cloud.service.cur.CurSourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final CurInsightCache insightCache;

    /** The tags an owned resource is expected to carry. Shared with the inventory scan. */
    private final List<String> requiredTags;

    public CurInsightsController(CloudAccountRepository cloudAccountRepository,
                                 CurSourceResolver curSourceResolver,
                                 CurAdvancedMetricsService metricsService,
                                 CurInsightCache insightCache,
                                 @Value("${tagging.compliance.required-tags}") List<String> requiredTags) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.curSourceResolver = curSourceResolver;
        this.metricsService = metricsService;
        this.insightCache = insightCache;
        this.requiredTags = requiredTags;
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
     * What each individual resource cost, most expensive first.
     *
     * <p>Capped server-side; the response carries the full total and count so a client can
     * say what it is not showing.
     */
    @GetMapping("/top-resources/{accountId}")
    public ResponseEntity<?> topResources(@PathVariable String accountId,
                                          @RequestParam(required = false) String period) {
        return withSource(accountId, period, (source, account, start, end) ->
                metricsService.getTopResources(source, account, start, end));
    }

    /**
     * How much of the bill can be attributed to an owner, and what cannot.
     *
     * <p>The required tags come from configuration rather than the request: they are a
     * policy the account is held to, and letting a caller pass its own would turn a
     * compliance number into whatever the caller wanted it to be.
     */
    @GetMapping("/tag-coverage/{accountId}")
    public ResponseEntity<?> tagCoverage(@PathVariable String accountId,
                                         @RequestParam(required = false) String period) {
        return withSource(accountId, period, (source, account, start, end) ->
                metricsService.getTagCoverage(source, account, start, end, requiredTags));
    }

    /**
     * Every resource with unattributed spend, as a file.
     *
     * <p>The panel folds this list on purpose — a thousand rows is a wall, not a work
     * queue. But bulk tagging is done by script, not by reading, and a spreadsheet is the
     * right shape for that, so the full finding is available here rather than paginated
     * into a table nobody would page through.
     */
    @GetMapping(value = "/tag-coverage/{accountId}/untagged.csv", produces = "text/csv")
    public ResponseEntity<?> untaggedCsv(@PathVariable String accountId,
                                         @RequestParam(required = false) String period) {
        ResponseEntity<?> result = withSource(accountId, period, (source, account, start, end) ->
                toCsv(metricsService.getAllUntaggedResources(source, account, start, end, requiredTags)));
        if (!result.getStatusCode().is2xxSuccessful()) {
            return result;
        }
        String filename = "untagged-spend-" + accountId + "-"
                + (period != null ? period : YearMonth.now()) + ".csv";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(result.getBody());
    }

    /**
     * Renders the rows as CSV.
     *
     * <p>Quotes every field rather than only the ones that need it: a resource name is
     * free text a customer chose, and one containing a comma would otherwise shift every
     * column after it — silently, in a file destined for a script.
     */
    private String toCsv(List<CurAdvancedMetricsService.UntaggedResource> rows) {
        StringBuilder csv = new StringBuilder("resource_id,name,service,region,unattributed_cost,missing_tags\n");
        for (CurAdvancedMetricsService.UntaggedResource r : rows) {
            csv.append(csvField(r.resourceId())).append(',')
                    .append(csvField(r.name())).append(',')
                    .append(csvField(r.service())).append(',')
                    .append(csvField(r.region())).append(',')
                    .append(String.format("%.4f", r.cost())).append(',')
                    .append(csvField(String.join(" ", r.missingTags())))
                    .append('\n');
        }
        return csv.toString();
    }

    private String csvField(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    /**
     * Spend under one tag, by value — the chargeback view.
     *
     * <p>Any tag in the report is allowed here, not just the required ones: "what did each
     * team spend" and "who is failing policy" are different questions, and the second set
     * of keys is not a useful limit on the first.
     */
    @GetMapping("/cost-by-tag/{accountId}")
    public ResponseEntity<?> costByTag(@PathVariable String accountId,
                                       @RequestParam String tagKey,
                                       @RequestParam(required = false) String period) {
        return withSource(accountId, period, (source, account, start, end) ->
                metricsService.getCostByTagValue(source, account, start, end, tagKey));
    }

    /**
     * Data transfer and network costs, grouped by what drives them, with the route each
     * charge took and the resources responsible for it.
     *
     * <p>Defaults to the current month — a breakdown of what has been billed, so it is
     * meaningful mid-month.
     *
     * <p>Returns an object rather than the bare list it used to, because the payload now
     * has to say whether the route and resource columns were in the report at all. An empty
     * route on a report that carries the column means "no route"; on one that does not it
     * means "unknown", and the two cannot be told apart from the rows alone.
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
        return insightCache.getOrCompute(accountId, () -> computePriceVsVolume(accountId, period));
    }

    private ResponseEntity<?> computePriceVsVolume(String accountId, String period) {
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
        // Every section on the Cost and FinOps pages that reads the report comes through
        // here, so this one wrap is what stops them waiting on Athena per visit.
        return insightCache.getOrCompute(accountId, () -> runWithSource(accountId, period, query));
    }

    private ResponseEntity<?> runWithSource(String accountId, String period, SourceQuery query) {
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
