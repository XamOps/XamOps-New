package com.xammer.cloud.controller.azure;

import com.xammer.cloud.service.azure.AzureCostInsightsService;
import com.xammer.cloud.service.azure.AzureCostInsightsService.BreakdownDimension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Azure counterpart of {@code CurInsightsController} and {@code GcpCostInsightsController},
 * deliberately down to the status codes.
 *
 * <p>The frontend treats any non-2xx from an insight endpoint as "this section does not apply to this
 * account" and hides it, which is why the failure modes are separated here rather than flattened into
 * an empty array:
 *
 * <ul>
 *   <li><b>409</b> — the subscription cannot answer this question. Either it is not connected, or the
 *       scope will not group by the dimension the section is built on. The reason is actionable, so it
 *       travels in the body for support to read; it is not shown to the customer, who sees the section
 *       simply absent.</li>
 *   <li><b>502</b> — Cost Management was reachable and the query failed: a missing <em>Cost Management
 *       Reader</em> assignment, a tenant policy restricting cost data to billing admins, or sustained
 *       throttling. Distinct from 409 because one is a fact about the subscription and the other is a
 *       fault, and telling a customer to reconfigure something that is already correct is worse than
 *       telling them nothing.</li>
 * </ul>
 *
 * <p>An empty 200 therefore means exactly one thing: the subscription really had no spend of this
 * kind. That distinction is the one the existing Azure cost endpoints lose by returning an empty list
 * on every failure path — {@code AzureDashboardController.getServiceTrend} answers a missing account,
 * a cold cache and a genuinely idle subscription with the same empty arrays.
 */
@RestController
@RequestMapping("/api/xamops/azure/cost-insights")
@Slf4j
public class AzureCostInsightsController {

    private final AzureCostInsightsService insightsService;

    public AzureCostInsightsController(AzureCostInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    /** Every billed line at invoice grain: service, region, meter, unit, quantity, cost. */
    @GetMapping("/charges-by-service/{subscriptionId}")
    public ResponseEntity<?> chargesByService(@PathVariable String subscriptionId,
                                              @RequestParam(required = false) String period,
                                              @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(subscriptionId, period, "charges-by-service",
                month -> insightsService.getChargesByService(subscriptionId, month, forceRefresh));
    }

    /**
     * What each individual resource cost.
     *
     * <p>409 when the scope will not group by {@code ResourceId} — a limit of the agreement behind the
     * subscription, not a subscription with no resources.
     */
    @GetMapping("/top-resources/{subscriptionId}")
    public ResponseEntity<?> topResources(@PathVariable String subscriptionId,
                                          @RequestParam(required = false) String period,
                                          @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(subscriptionId, period, "top-resources",
                month -> insightsService.getTopResources(subscriptionId, month, forceRefresh));
    }

    /**
     * One resource in depth: its month-over-month movement, which meters moved, and its daily spend.
     *
     * <p>Serves the resource drill-down panel. 409 under the same condition as the resource table — a
     * scope that will not group by {@code ResourceId} — so a reader who can see the table can open the
     * panel, and one who cannot never reaches it.
     */
    @GetMapping("/resource-change/{subscriptionId}")
    public ResponseEntity<?> resourceChange(@PathVariable String subscriptionId,
                                            @RequestParam String resourceId,
                                            @RequestParam(required = false) String period,
                                            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        if (resourceId.isBlank()) {
            // The resource table groups charges belonging to no resource — Marketplace, support — under
            // one heading. There is no per-resource story there, so the panel is not offered for them.
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "A resource id is required",
                    "detail", "Charges that Azure attributes to no resource have no breakdown."));
        }
        return section(subscriptionId, period, "resource-change",
                month -> insightsService.getResourceChange(subscriptionId, resourceId, month, forceRefresh));
    }

    /** Network spend by category, with the route each charge took and the resources responsible. */
    @GetMapping("/network/{subscriptionId}")
    public ResponseEntity<?> network(@PathVariable String subscriptionId,
                                     @RequestParam(required = false) String period,
                                     @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(subscriptionId, period, "network",
                month -> insightsService.getNetworkCosts(subscriptionId, month, forceRefresh));
    }

    /**
     * How much of each service's month-on-month change was rate, and how much was usage.
     *
     * <p>409 when the scope will not aggregate {@code UsageQuantity}: without quantities the split
     * would attribute every movement to price, which describes the query rather than the bill.
     */
    @GetMapping("/price-vs-volume/{subscriptionId}")
    public ResponseEntity<?> priceVsVolume(@PathVariable String subscriptionId,
                                           @RequestParam(required = false) String period,
                                           @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(subscriptionId, period, "price-vs-volume",
                month -> insightsService.getPriceVsVolume(subscriptionId, month, forceRefresh));
    }

    /** Usage against purchases, refunds, unused commitment and rounding, on an amortized view. */
    @GetMapping("/charge-types/{subscriptionId}")
    public ResponseEntity<?> chargeTypes(@PathVariable String subscriptionId,
                                         @RequestParam(required = false) String period,
                                         @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(subscriptionId, period, "charge-types",
                month -> insightsService.getChargeTypeBreakdown(subscriptionId, month, forceRefresh));
    }

    /** Reservation and savings plan spend that bought nothing. */
    @GetMapping("/unused-commitment/{subscriptionId}")
    public ResponseEntity<?> unusedCommitment(@PathVariable String subscriptionId,
                                              @RequestParam(required = false) String period,
                                              @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(subscriptionId, period, "unused-commitment",
                month -> insightsService.getUnusedCommitment(subscriptionId, month, forceRefresh));
    }

    /**
     * Spend for a date range grouped by one dimension — what the breakdown donut and table read.
     *
     * <p>Takes dates rather than a period because this is the section the page lets a reader filter,
     * and a range that is not a whole month is a question the existing Azure cost endpoints cannot be
     * asked: they serve a fixed month-to-date figure out of the ingestion cache.
     *
     * <p>Answers the currency alongside the rows. The figures are meaningless without it and every
     * Azure cost component previously assumed rupees, so shipping the two together is what stops the
     * next component guessing again.
     */
    @GetMapping("/breakdown/{subscriptionId}")
    public ResponseEntity<?> breakdown(@PathVariable String subscriptionId,
                                       @RequestParam String startDate,
                                       @RequestParam String endDate,
                                       @RequestParam(defaultValue = "SERVICE") String groupBy,
                                       @RequestParam(required = false) String tagKey,
                                       @RequestParam(defaultValue = "false") boolean forceRefresh) {
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startDate);
            end = LocalDate.parse(endDate);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid date range",
                    "detail", "Expected YYYY-MM-DD for startDate and endDate."));
        }
        if (end.isBefore(start)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid date range",
                    "detail", "endDate " + end + " is before startDate " + start + "."));
        }

        boolean byTag = "TAG".equalsIgnoreCase(groupBy);
        if (byTag && (tagKey == null || tagKey.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing tag key",
                    "detail", "groupBy=TAG needs a tagKey to group the values of."));
        }

        BreakdownDimension dimension = null;
        if (!byTag) {
            try {
                dimension = BreakdownDimension.valueOf(groupBy.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Unsupported groupBy",
                        "detail", "Expected TAG or one of " + java.util.Arrays.toString(
                                BreakdownDimension.values()) + ", got '" + groupBy + "'."));
            }
        }

        BreakdownDimension resolved = dimension;
        return guarded(subscriptionId, "breakdown", () -> {
            Object rows = byTag
                    ? insightsService.getCostByTag(subscriptionId, start, end, tagKey, forceRefresh)
                    : insightsService.getCostByDimension(subscriptionId, start, end, resolved,
                            forceRefresh);
            Map<String, Object> body = new HashMap<>();
            body.put("costs", rows);
            body.put("currency", insightsService.currencyOf(subscriptionId));
            body.put("groupBy", byTag ? "TAG" : resolved.name());
            if (byTag) {
                body.put("tagKey", tagKey);
            }
            return body;
        });
    }

    /**
     * Every tag key Cost Management knows about, for the chargeback picker.
     *
     * <p>Always 200 with a list, empty included: an absent picker is a smaller loss than a 409 the
     * page would read as "hide the whole filter bar".
     */
    @GetMapping("/tag-keys/{subscriptionId}")
    public ResponseEntity<?> tagKeys(@PathVariable String subscriptionId,
                                     @RequestParam(defaultValue = "false") boolean forceRefresh) {
        Optional<String> unavailable = insightsService.unavailableReason(subscriptionId);
        if (unavailable.isPresent()) {
            return ResponseEntity.ok(java.util.List.of());
        }
        try {
            return ResponseEntity.ok(insightsService.getTagKeys(subscriptionId, forceRefresh));
        } catch (Exception e) {
            log.debug("[Azure] Tag keys unavailable for {}: {}", subscriptionId, e.getMessage());
            return ResponseEntity.ok(java.util.List.of());
        }
    }

    /**
     * The currency every Azure cost figure for this subscription is denominated in.
     *
     * <p>Its own endpoint because the page needs it before any section has loaded — the KPI cards
     * render a symbol on first paint — and because it is the one thing here that is never unavailable:
     * a subscription whose currency has not been ingested yet is reported as USD rather than as a
     * failure, which is what the components already assumed before they assumed rupees.
     */
    @GetMapping("/currency/{subscriptionId}")
    public ResponseEntity<Map<String, String>> currency(@PathVariable String subscriptionId) {
        return ResponseEntity.ok(Map.of("currency", insightsService.currencyOf(subscriptionId)));
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────

    /**
     * Shared shape for every month-scoped section: parse the period, check the subscription can
     * answer, run.
     *
     * <p>The availability check comes first and costs no query, so a subscription that will never have
     * these sections does not spend a Cost Management call — the most throttled endpoint in the Azure
     * integration — being told so.
     */
    private ResponseEntity<?> section(String subscriptionId, String period, String name,
                                      Function<YearMonth, Object> compute) {
        YearMonth month;
        try {
            month = period != null && !period.isBlank() ? YearMonth.parse(period) : YearMonth.now();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid period",
                    "detail", "Expected YYYY-MM, got '" + period + "'"));
        }
        return guarded(subscriptionId, name, () -> compute.apply(month));
    }

    /**
     * Runs a section, turning its two "cannot answer" cases into 409 and everything else into 502.
     *
     * <p>A null body is the service's way of saying the scope refused the dimension the section is
     * built on. It is answered as 409 rather than as an empty 200 for the same reason the absent
     * account is: an empty section and an impossible one look identical on screen, and only one of
     * them should ever prompt someone to go looking for the missing spend.
     */
    private ResponseEntity<?> guarded(String subscriptionId, String name, Supplier<Object> compute) {
        Optional<String> unavailable = insightsService.unavailableReason(subscriptionId);
        if (unavailable.isPresent()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Cost insights are not available for subscription " + subscriptionId,
                    "detail", unavailable.get()));
        }
        try {
            Object body = compute.get();
            if (body == null) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "This breakdown is not available for subscription " + subscriptionId,
                        "detail", "Azure Cost Management would not group this subscription's spend by"
                                + " the dimension this breakdown is built on. Which dimensions are"
                                + " available depends on the agreement behind the subscription."));
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.warn("[Azure] Cost insight '{}' failed for subscription {}: {}", name, subscriptionId,
                    e.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "error", "Could not read Azure Cost Management",
                    "detail", String.valueOf(e.getMessage())));
        }
    }
}
