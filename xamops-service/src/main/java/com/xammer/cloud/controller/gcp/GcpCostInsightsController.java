package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.service.gcp.GcpCostInsightsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The GCP counterpart of {@code CurInsightsController}, deliberately down to the status codes.
 *
 * <p>The frontend treats any non-2xx from an insight endpoint as "this section does not apply to
 * this account" and hides it, which is why the two failure modes are separated here rather than
 * flattened into an empty array:
 *
 * <ul>
 *   <li><b>409</b> — the project cannot answer these questions at all: reseller-billed, or no
 *       readable billing export. Nothing the section can usefully draw, and the reason is
 *       actionable, so it travels in the body.</li>
 *   <li><b>502</b> — the export exists and the query failed. Distinct from 409 because one is a
 *       configuration fact and the other is a fault, and telling a customer to fix an export they
 *       already configured is worse than telling them nothing.</li>
 * </ul>
 *
 * <p>An empty 200 therefore means exactly one thing: the project really had no spend of this kind.
 * That distinction is the one the existing GCP cost endpoints lose by returning an empty list on
 * every failure path.
 */
@RestController
@RequestMapping("/api/xamops/gcp/cost-insights")
@Slf4j
public class GcpCostInsightsController {

    private final GcpCostInsightsService insightsService;

    public GcpCostInsightsController(GcpCostInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    /** Network spend by category, with the route each charge took. */
    @GetMapping("/network/{projectId}")
    public ResponseEntity<?> network(@PathVariable String projectId,
                                     @RequestParam(required = false) String period,
                                     @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(projectId, period, "network",
                month -> insightsService.getNetworkCosts(projectId, month, forceRefresh));
    }

    /** Net spend against list price, and the resulting discount rate. */
    @GetMapping("/savings-rate/{projectId}")
    public ResponseEntity<?> savingsRate(@PathVariable String projectId,
                                         @RequestParam(required = false) String period,
                                         @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(projectId, period, "savings-rate",
                month -> insightsService.getSavingsRate(projectId, month, forceRefresh));
    }

    /** Usage vs tax vs adjustments, and credits split by the kind of discount they are. */
    @GetMapping("/charge-types/{projectId}")
    public ResponseEntity<?> chargeTypes(@PathVariable String projectId,
                                         @RequestParam(required = false) String period,
                                         @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(projectId, period, "charge-types",
                month -> insightsService.getChargeTypeBreakdown(projectId, month, forceRefresh));
    }

    /** Every billed line at invoice grain: service, region, SKU, unit, quantity. */
    @GetMapping("/charges-by-service/{projectId}")
    public ResponseEntity<?> chargesByService(@PathVariable String projectId,
                                             @RequestParam(required = false) String period,
                                             @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(projectId, period, "charges-by-service",
                month -> insightsService.getChargesByService(projectId, month, forceRefresh));
    }

    /**
     * What each individual resource cost.
     *
     * <p>409 on a standard export, which records no resource name — a configuration fact with a
     * fix, not a fault and not an account with no resources.
     */
    @GetMapping("/top-resources/{projectId}")
    public ResponseEntity<?> topResources(@PathVariable String projectId,
                                          @RequestParam(required = false) String period,
                                          @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(projectId, period, "top-resources",
                month -> insightsService.getTopResources(projectId, month, forceRefresh));
    }

    /**
     * One resource in depth: its month-over-month movement, which SKUs moved, and its daily spend.
     *
     * <p>Serves the resource drill-down panel. 409 on the standard export, which records no resource
     * name — the same condition that makes the resource table unavailable, so a reader who can see the
     * table can open the panel and one who cannot never reaches it.
     */
    @GetMapping("/resource-change/{projectId}")
    public ResponseEntity<?> resourceChange(@PathVariable String projectId,
                                            @RequestParam String resourceId,
                                            @RequestParam(required = false) String period,
                                            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        if (resourceId.isBlank()) {
            // The resource table groups charges attributed to no resource under one heading. There is no
            // per-resource story to tell about them, so the panel is not offered.
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "A resource id is required",
                    "detail", "Charges the export attributes to no resource have no breakdown."));
        }
        return section(projectId, period, "resource-change",
                month -> insightsService.getResourceChange(projectId, resourceId, month, forceRefresh));
    }

    /** How much of each service's change was rate, and how much was usage. */
    @GetMapping("/price-vs-volume/{projectId}")
    public ResponseEntity<?> priceVsVolume(@PathVariable String projectId,
                                           @RequestParam(required = false) String period,
                                           @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return section(projectId, period, "price-vs-volume",
                month -> insightsService.getPriceVsVolume(projectId, month, forceRefresh));
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────

    /**
     * Shared shape for every section: resolve the month, check the project can answer, run.
     *
     * <p>The availability check comes first and costs no query, so an account that will never have
     * these sections does not pay a BigQuery scan to be told so.
     */
    private ResponseEntity<?> section(String projectId, String period, String name,
                                      Function<YearMonth, Object> compute) {
        YearMonth month;
        try {
            month = period != null && !period.isBlank() ? YearMonth.parse(period) : YearMonth.now();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid period",
                    "detail", "Expected YYYY-MM, got '" + period + "'"));
        }

        Optional<String> unavailable = insightsService.unavailableReason(projectId);
        if (unavailable.isPresent()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Cost insights are not available for project " + projectId,
                    "detail", unavailable.get()));
        }

        try {
            Object body = compute.apply(month);
            if (body == null) {
                // A section the export cannot answer even though the export itself is fine —
                // today only the savings rate, when cost_at_list predates this export.
                return ResponseEntity.status(409).body(Map.of(
                        "error", "This breakdown is not available for project " + projectId,
                        "detail", "The billing export does not carry the columns it needs."
                                + " A newer export includes them."));
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.warn("[GCP] Cost insight '{}' failed for project {}: {}", name, projectId,
                    e.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "error", "Could not read the billing export",
                    "detail", String.valueOf(e.getMessage())));
        }
    }
}
