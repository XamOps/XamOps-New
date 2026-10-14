package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.cur.CostBasis;
import com.xammer.cloud.service.cur.CurAdvancedMetricsService;
import com.xammer.cloud.service.cur.CurInsightCache;
import com.xammer.cloud.service.cur.CurSourceResolver;
import com.xammer.cloud.service.cur.ReportCannotAnswerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final CloudAccountAccessService accessService;

    /** The tags an owned resource is expected to carry. Shared with the inventory scan. */
    private final List<String> requiredTags;

    public CurInsightsController(CloudAccountRepository cloudAccountRepository,
                                 CurSourceResolver curSourceResolver,
                                 CurAdvancedMetricsService metricsService,
                                 CurInsightCache insightCache,
                                 CloudAccountAccessService accessService,
                                 @Value("${tagging.compliance.required-tags}") List<String> requiredTags) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.curSourceResolver = curSourceResolver;
        this.metricsService = metricsService;
        this.insightCache = insightCache;
        this.accessService = accessService;
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
                                              @RequestParam(required = false) String period,
                                              @RequestParam(required = false) String basis) {
        Optional<CostBasis> costBasis = basisOf(basis);
        if (costBasis.isEmpty()) {
            return invalidBasis(basis);
        }
        return withSource(accountId, period, (source, account, start, end) ->
                metricsService.getChargesByService(source, account, start, end, costBasis.get()));
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
                                         @RequestParam(required = false) String period,
                                         @RequestParam(required = false) String basis) {
        Optional<CostBasis> costBasis = basisOf(basis);
        if (costBasis.isEmpty()) {
            return invalidBasis(basis);
        }
        return withSource(accountId, period, (source, account, start, end) ->
                metricsService.getChargeTypeBreakdown(source, account, start, end, costBasis.get()));
    }

    /**
     * One service's resources, ranked, capped inside that service.
     *
     * <p>{@code service} is declared optional and validated in the body rather than
     * {@code @RequestParam String service}, which Spring would reject with its own
     * MissingServletRequestParameterException body. The sentence a caller gets back — "this
     * endpoint describes one service's resources, use /top-resources for the whole account" — is
     * the useful half of that error, and only this method can write it.
     *
     * <p>Validation order on this handler: entitlement, then the basis, then this endpoint's own
     * parameters, then the period inside {@link #withSource}. Entitlement first because the 400s
     * below are about {@code service}, and which service names are rejected is the kind of thing a
     * caller who holds no account should not be able to probe.
     *
     * <p>Not an invariant of every handler here, and deliberately so. {@link #chargesByService} and
     * {@link #chargeTypes} parse the basis before entering {@code withSource}, where the entitlement
     * check lives, so an unentitled caller sending {@code basis=amortised} gets the 400 rather than
     * the 404. That is the same precedence {@link #resourceChange} uses for its blank-id 400 and for
     * the same reason: the response is a pure function of the parameter, discloses nothing about the
     * account, and is byte-identical for every caller — a malformed request is malformed for all of
     * them.
     */
    @GetMapping("/service-resources/{accountId}")
    public ResponseEntity<?> serviceResources(@PathVariable String accountId,
                                              @RequestParam(required = false) String service,
                                              @RequestParam(required = false) String period,
                                              @RequestParam(required = false) String basis) {
        // Checked here and again inside withSource, which costs one extra indexed lookup on the
        // allowed path and buys the ordering above: a caller who holds no account cannot learn
        // anything from the shape of this endpoint's 400s. A denied caller never reaches the
        // second check, so nothing is logged twice.
        ResponseEntity<?> denied = denyIfNotEntitled(accountId);
        if (denied != null) {
            return denied;
        }
        Optional<CostBasis> costBasis = basisOf(basis);
        if (costBasis.isEmpty()) {
            return invalidBasis(basis);
        }
        if (service == null || service.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "A service is required",
                    "detail", "This endpoint describes one service's resources."
                            + " Use /top-resources for the whole account."));
        }
        if (service.length() > MAX_SERVICE_NAME) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Service name too long",
                    "detail", "A billing service name is at most " + MAX_SERVICE_NAME
                            + " characters."));
        }
        return withSource(accountId, period, (source, account, start, end) ->
                metricsService.getServiceResources(source, account, service, start, end,
                        costBasis.get()));
    }

    /**
     * Which of this card's optional questions the report can answer. Scans no data.
     *
     * <p>Deliberately NOT routed through {@link #withSource}: the answer already comes from the
     * column catalog's own in-process cache — 6h on success, 10 min on failure, zero bytes scanned
     * either way — so a second cache in front of it buys nothing and costs correctness.
     * {@code CurInsightCache} would hold a 200 for 1h on the open month and 6h on a closed one,
     * with stale-while-revalidate on top, so a permissions blip that made every flag false would
     * disable the basis toggle and the unit footnote for hours after the catalog itself had
     * recovered — defeating the whole point of that 10-minute failure TTL.
     */
    @GetMapping("/capabilities/{accountId}")
    public ResponseEntity<?> capabilities(@PathVariable String accountId,
                                          @RequestParam(required = false) String period) {
        ResponseEntity<?> denied = denyIfNotEntitled(accountId);
        if (denied != null) {
            return denied;
        }
        Optional<YearMonth> target = parsePeriod(period);
        if (target.isEmpty()) {
            return invalidPeriod(period);
        }
        // runWithSource, not withSource: same 404 / 409 / 422 / 502 behaviour, no HTTP-tier cache.
        return runWithSource(accountId, target.get(), (source, account, start, end) ->
                metricsService.getReportCapabilities(source, account));
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
     * One resource in depth: its month-over-month movement, which usage types moved, and its daily spend.
     *
     * <p>Serves the resource drill-down panel. Scoped to a single resource id, so unlike the sections
     * around it the cost of this query barely grows with the size of the account — which is what makes
     * it safe to run on a click rather than precomputing it for every resource in the table.
     *
     * <p>Both months must be inside the report's coverage, so the resolver is asked about the earlier
     * one. A report that does not reach back far enough cannot produce a comparison, and a silently
     * one-sided one would read as the resource having appeared from nothing.
     */
    @GetMapping("/resource-change/{accountId}")
    public ResponseEntity<?> resourceChange(@PathVariable String accountId,
                                            @RequestParam String resourceId,
                                            @RequestParam(required = false) String service,
                                            @RequestParam(required = false) String region,
                                            @RequestParam(required = false) String period) {
        if (resourceId.isBlank()) {
            // The resource table groups charges that belong to no resource — tax, support — under one
            // heading. There is no per-resource story to tell about them, so the panel is not offered.
            // Before the entitlement check on purpose: a malformed request is malformed for every
            // caller, and answering 400 first keeps that response identical for all of them.
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "A resource id is required",
                    "detail", "Charges that belong to no resource have no per-resource breakdown."));
        }
        ResponseEntity<?> denied = denyIfNotEntitled(accountId);
        if (denied != null) {
            return denied;
        }
        Optional<YearMonth> target = parsePeriod(period);
        if (target.isEmpty()) {
            return invalidPeriod(period);
        }
        return insightCache.getOrCompute(accountId,
                () -> computeResourceChange(accountId, resourceId, service, region, target.get()));
    }

    private ResponseEntity<?> computeResourceChange(String accountId, String resourceId,
                                                    String service, String region, YearMonth current) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CloudAccount account = accounts.get(0);

        YearMonth prior = current.minusMonths(1);

        /*
         * Only the selected month is required.
         *
         * This used to resolve across both months, which meant a report younger than two months could not
         * answer at all and the panel opened onto "no detail available" — for a resource whose current
         * month was sitting in the report, queryable, alongside the daily series. A CUR begins at the
         * month it was created and never backfills, so that is the normal state for a newly connected
         * report rather than an edge case.
         *
         * The comparison degrades instead: the prior month is probed separately and, when it is outside
         * coverage, the payload says so rather than reporting zeros as a real earlier month.
         */
        Optional<CurSource> source = curSourceResolver.resolve(
                account, current.atDay(1), current.atEndOfMonth());
        if (source.isEmpty()) {
            CurSourceResolver.Coverage coverage = curSourceResolver.describeCoverage(
                    account, current.atDay(1), current.atEndOfMonth(), false);
            return noCurSource(accountId, coverage.message());
        }

        boolean comparePrior = curSourceResolver
                .resolve(account, prior.atDay(1), prior.atEndOfMonth())
                .isPresent();

        try {
            return ResponseEntity.ok(metricsService.getResourceChange(source.get(), account, resourceId,
                    service, region,
                    current.atDay(1), endOf(current),
                    prior.atDay(1), prior.atEndOfMonth(),
                    comparePrior));
        } catch (Exception e) {
            return queryFailed(accountId, e);
        }
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
        ResponseEntity<?> denied = denyIfNotEntitled(accountId);
        if (denied != null) {
            return denied;
        }
        Optional<YearMonth> target = parsePeriod(period);
        if (target.isEmpty()) {
            return invalidPeriod(period);
        }
        return insightCache.getOrCompute(accountId, () -> computePriceVsVolume(accountId, target.get()));
    }

    private ResponseEntity<?> computePriceVsVolume(String accountId, YearMonth current) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CloudAccount account = accounts.get(0);

        YearMonth prior = current.minusMonths(1);

        Optional<CurSource> source = curSourceResolver.resolve(
                account, prior.atDay(1), current.atEndOfMonth());
        if (source.isEmpty()) {
            // Unlike the other sections this genuinely needs both months — a one-sided comparison would
            // read as every service having appeared from nothing. But the reason is worth stating: on a
            // report younger than two months this is the expected answer for every month it offers, and
            // the generic "connect a CUR" text sends someone to reconnect one that is already working.
            CurSourceResolver.Coverage coverage = curSourceResolver.describeCoverage(
                    account, prior.atDay(1), current.atEndOfMonth(), false);
            return noCurSource(accountId, coverage.message());
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

    /** Twice the longest real AWS product name. See {@link #serviceResources} for why it is bounded. */
    private static final int MAX_SERVICE_NAME = 128;

    private ResponseEntity<?> withSource(String accountId, String period, SourceQuery query) {
        ResponseEntity<?> denied = denyIfNotEntitled(accountId);
        if (denied != null) {
            return denied;
        }
        Optional<YearMonth> target = parsePeriod(period);
        if (target.isEmpty()) {
            return invalidPeriod(period);
        }
        // Every section on the Cost and FinOps pages that reads the report comes through
        // here, so this one wrap is what stops them waiting on Athena per visit.
        return insightCache.getOrCompute(accountId, () -> runWithSource(accountId, target.get(), query));
    }

    private ResponseEntity<?> runWithSource(String accountId, YearMonth target, SourceQuery query) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CloudAccount account = accounts.get(0);

        LocalDate start = target.atDay(1);
        LocalDate end = endOf(target);

        Optional<CurSource> source = curSourceResolver.resolve(account, start, end);
        if (source.isEmpty()) {
            // The two-argument form. `noCurSource(accountId)` sends a reader to connect a report
            // that is already connected; the resolver's own sentence names the actual limit ("this
            // report starts at 2026-09"), which is the difference between understanding an empty
            // month and concluding the product is broken.
            return noCurSource(accountId,
                    curSourceResolver.describeCoverage(account, start, end, false).message());
        }

        try {
            return ResponseEntity.ok(query.run(source.get(), account, start, end));
        } catch (ReportCannotAnswerException e) {
            return cannotAnswer(accountId, e);
        } catch (Exception e) {
            return queryFailed(accountId, e);
        }
    }

    /**
     * The requested month, or empty when the parameter is unusable.
     *
     * <p>Blank counts as absent-and-invalid: {@code ?period=} arrives as the empty string, which
     * {@code YearMonth.parse} throws on just as readily as {@code 2026-13}.
     *
     * <p>Called from the handlers rather than from the compute methods below them. Those are the
     * cache's compute suppliers, and a 400 decided there is a request-parameter check on a code
     * path that also runs on {@code awsTaskExecutor} during a background refresh, where there is no
     * request left to validate. Three call sites had three behaviours before this: one returned
     * 400, the other two parsed outside their try and answered 500.
     */
    private Optional<YearMonth> parsePeriod(String period) {
        if (period == null) {
            return Optional.of(YearMonth.now());
        }
        if (period.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.parse(period));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 400 naming the expected format and what arrived, so the caller can see which it got wrong. */
    private ResponseEntity<Map<String, String>> invalidPeriod(String period) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid period",
                "detail", "Expected YYYY-MM, got '" + period + "'"));
    }

    /**
     * The requested cost basis, or empty when the value is not one of the two.
     *
     * <p>Outside {@link #withSource}, because an unparseable basis must never reach a query and
     * {@code withSource} already has a return type that cannot carry a parse failure cleanly.
     */
    private Optional<CostBasis> basisOf(String raw) {
        try {
            return Optional.of(CostBasis.from(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 400 listing both accepted values, mirroring {@link #invalidPeriod}. */
    private ResponseEntity<Map<String, String>> invalidBasis(String raw) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid cost basis",
                "detail", "Expected 'unblended' or 'amortized', got '" + raw + "'"));
    }

    /**
     * Null when this caller may read this account, or the response to return instead.
     *
     * <p>Call this on the request thread, before {@code insightCache.getOrCompute} — from a
     * handler, or from {@link #withSource}, which is the shared prologue for ten of them. Never
     * from {@code runWithSource}, {@code computeResourceChange} or {@code computePriceVsVolume}:
     * those are the cache's compute suppliers, and a check inside a supplier is wrong twice. It is
     * skipped on every cache hit, since {@code getOrCompute} returns the stored json without
     * calling the supplier and the key carries no client id — so tenant A's cached invoice would be
     * served to tenant B. And it runs on {@code awsTaskExecutor} during a stale-while-revalidate
     * refresh where there is no security context, so it would fail closed on a legitimate refresh
     * and the 404 it returned would trip {@code refreshInBackground}'s {@code is4xxClientError()}
     * branch and evict a good entry.
     *
     * <p>404 rather than 403 for "not yours", matching {@code AgentController}: unknown and
     * not-yours give the identical answer, so the endpoint cannot be used to discover which account
     * ids exist.
     */
    private ResponseEntity<?> denyIfNotEntitled(String accountId) {
        try {
            accessService.requireAwsAccess(currentCaller(), accountId);
            return null;
        } catch (AccessDeniedException denied) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * The authenticated principal, or null.
     *
     * <p>Read here rather than declared as {@code @AuthenticationPrincipal} on twelve handler
     * signatures: one place to change, and a thirteenth endpoint added later inherits the guard
     * instead of needing to remember it. A non-{@code ClientUserDetails} principal yields null,
     * which {@code requireAwsAccess} rejects by failing closed — so the endpoint 404s rather than
     * NPEs. Under impersonation {@code ImpersonationJwtFilter} installs a {@code ClientUserDetails}
     * carrying the impersonated user's id, so the check evaluates the session the support engineer
     * is actually looking at.
     */
    private ClientUserDetails currentCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof ClientUserDetails caller ? caller : null;
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

    /**
     * 409 — no report covers the requested month.
     *
     * <p>Every caller now passes the resolver's reason; the one-argument overload that answered with
     * the generic sentence alone is gone, because it was what made ten endpoints explain the same
     * absence differently from the two that already knew better.
     *
     * @param reason the resolver's own sentence, which names the actual limit — "this report starts at
     *               2026-09" is the difference between a reader understanding why a month is empty and
     *               concluding the product is broken. Falls back to the generic explanation when the
     *               resolver has nothing specific to say.
     */
    private ResponseEntity<Map<String, String>> noCurSource(String accountId, String reason) {
        return ResponseEntity.status(409).body(Map.of(
                "error", "No Cost and Usage Report is available for account " + accountId,
                "detail", reason != null && !reason.isBlank() ? reason
                        : "These insights are derived from CUR columns that the Cost Explorer API"
                        + " does not expose, so there is no fallback. Connect a CUR for this account."));
    }

    /**
     * 422 — the report is readable and this question cannot be put to it.
     *
     * <p>Not 409: that means "this account has no CUR" and the client hides the whole card on it.
     * This is a readable report that cannot answer one specific question, and the card must stay so
     * the control that recovers from it is still on screen.
     *
     * <p>Logged at INFO rather than WARN. It is a property of the export rather than a fault, and
     * the UI prevents it being reached at all in the normal case.
     */
    private ResponseEntity<Map<String, Object>> cannotAnswer(String accountId,
                                                             ReportCannotAnswerException e) {
        logger.info("[CUR] Account {} asked for {}, which this report cannot answer — missing {}",
                accountId, e.question(), e.missingColumns());
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "This report cannot answer " + e.question(),
                "detail", String.valueOf(e.getMessage()),
                "missingColumns", e.missingColumns()));
    }

    private ResponseEntity<Map<String, String>> queryFailed(String accountId, Exception e) {
        logger.warn("[CUR] Insight query failed for account {}: {}", accountId, e.getMessage());
        return ResponseEntity.status(502).body(Map.of(
                "error", "Could not read the Cost and Usage Report",
                "detail", String.valueOf(e.getMessage())));
    }
}
