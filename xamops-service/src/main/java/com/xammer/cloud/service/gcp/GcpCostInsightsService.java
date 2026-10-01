package com.xammer.cloud.service.gcp;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.util.PriceVolumeSplit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The columns of the GCP billing export the cost page never read.
 *
 * <p>{@code GcpCostService} answers "what did this project spend, grouped by service, region or
 * project" from eight columns. That is the same question the AWS Cost Explorer path answered
 * before the Cost and Usage Report work, and it has the same limit: it says what was billed and
 * nothing about why. This class is the GCP counterpart of {@code CurAdvancedMetricsService} and
 * reads the rest of the schema — {@code sku.description}, {@code usage.amount_in_pricing_units},
 * {@code cost_at_list}, {@code cost_type} and {@code credits[].type}.
 *
 * <h2>The DTOs are deliberately identical to the AWS ones</h2>
 * Field for field, name for name. The Cost page's network, price-vs-volume and charge-type
 * sections are already built, tested and understood; making the GCP payload the same shape means
 * they render GCP data with no second implementation to keep in step. The alternative — a parallel
 * set of components — is how two pages that should agree end up disagreeing.
 *
 * <h2>Where this cannot answer</h2>
 * Two cases, both reported rather than returned as zero:
 *
 * <ul>
 *   <li><b>Reseller-billed projects.</b> Their costs live in a flattened per-tenant table with no
 *       {@code sku}, no {@code usage} and no {@code credits} array, and it reports list price as
 *       the cost. None of these sections can be computed from it, and a savings rate in
 *       particular would be exactly zero by construction — a fact about the query, not the
 *       customer.</li>
 *   <li><b>Older exports missing a column.</b> BigQuery fails the whole query when a selected
 *       column does not exist, so {@code cost_at_list} is probed for rather than assumed, the same
 *       reason {@code CurColumnCatalog} exists on the AWS side. The probe reads table metadata and
 *       scans no data.</li>
 * </ul>
 */
@Service
@Slf4j
public class GcpCostInsightsService {

    /**
     * Rows below this round to zero on screen.
     *
     * <p>Applied as a {@code HAVING} on net cost, so a charge fully offset by a credit drops out —
     * which is correct here: the reader is looking for what to act on, and a line that cost
     * nothing after discounts is not it.
     */
    private static final String MIN_COST = "0.001";

    /**
     * Ceiling on resources returned in one response.
     *
     * <p>Grouping by resource is by far the widest aggregation here, and every row crosses the wire
     * and renders. Ordered by cost, the tail beyond this is rounding; the true total travels
     * separately so nothing is misreported. Same figure as the AWS side, for the same reason.
     */
    private static final int MAX_RESOURCES = 500;

    /**
     * A table's column list changes only when Google revises the export schema, so this can be
     * cached hard. Short enough that a newly-added column is picked up the same day.
     */
    private static final Duration SCHEMA_TTL = Duration.ofHours(6);

    /**
     * How long a section is served from Redis.
     *
     * <p>Not a convenience. BigQuery bills per byte scanned, and these queries read more columns
     * than the existing cost path — so an uncached section would charge the customer every time
     * anyone opened the page, for figures that change a few times a day at most. The same
     * reasoning produced {@code CurInsightCache} on the AWS side.
     */
    private static final int SECTION_TTL_MINUTES = 6 * 60;

    /**
     * How long an empty or failed section is remembered. Deliberately short: an empty result is
     * usually a transient failure, and caching one for hours turns a blip into the day's answer.
     */
    private static final int EMPTY_TTL_MINUTES = 5;

    private final GcpClientProvider gcpClientProvider;
    private final GcpBillingStatusService billingStatusService;
    private final CloudAccountRepository cloudAccountRepository;
    private final RedisCacheService redisCacheService;

    /** Top-level column names per export table, from the metadata probe. */
    private final Map<String, CachedSchema> schemaCache = new ConcurrentHashMap<>();

    public GcpCostInsightsService(GcpClientProvider gcpClientProvider,
                                  GcpBillingStatusService billingStatusService,
                                  CloudAccountRepository cloudAccountRepository,
                                  RedisCacheService redisCacheService) {
        this.gcpClientProvider = gcpClientProvider;
        this.billingStatusService = billingStatusService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.redisCacheService = redisCacheService;
    }

    /**
     * Serves a section from Redis, computing it only on a miss.
     *
     * @param hasRows whether a computed result is worth the long TTL; an empty one gets
     *                {@link #EMPTY_TTL_MINUTES} so the next visit retries
     */
    private <T> T cached(String key, TypeReference<T> type, boolean forceRefresh,
                         Supplier<T> compute, Predicate<T> hasRows) {
        if (forceRefresh) {
            redisCacheService.evict(key);
        } else {
            Optional<T> hit = redisCacheService.get(key, type);
            if (hit.isPresent()) {
                return hit.get();
            }
        }
        T fresh = compute.get();
        redisCacheService.put(key, fresh,
                fresh != null && hasRows.test(fresh) ? SECTION_TTL_MINUTES : EMPTY_TTL_MINUTES);
        return fresh;
    }

    private static String key(String section, String gcpProjectId, YearMonth month) {
        return "gcp:cost-insight:" + section + ":" + gcpProjectId + ":" + month;
    }

    // ── Availability ────────────────────────────────────────────────────────────

    /**
     * Why these sections cannot be shown for a project, or empty when they can.
     *
     * <p>Checked before any query so the caller can answer "this is not available here" without a
     * BigQuery round trip, and so the UI can hide the sections rather than draw empty ones. An
     * empty section and an unavailable one look identical and need opposite things said about
     * them.
     */
    public Optional<String> unavailableReason(String gcpProjectId) {
        if (cloudAccountRepository.findCspBillingConfigByGcpProjectId(gcpProjectId).isPresent()) {
            return Optional.of("This project is reseller-billed. Its costs come from a flattened"
                    + " dataset that carries no SKU, usage or credit detail, so these breakdowns"
                    + " cannot be computed for it.");
        }
        if (source(gcpProjectId).isEmpty()) {
            return Optional.of("No BigQuery billing export could be read for this project."
                    + " See /api/xamops/gcp/costs/billing-status for the reason.");
        }
        return Optional.empty();
    }

    // ── Network and data transfer ───────────────────────────────────────────────

    /**
     * Network spend, split into the categories that drive it, with the route each charge took.
     *
     * <p>Routinely the largest cost nobody can see, for the same reason as on AWS: a service
     * breakdown files inter-zone traffic under whichever service moved the bytes and Cloud NAT
     * under Networking, so the network total never appears anywhere as one number.
     *
     * <p>Categorisation and routing happen in Java rather than in a SQL {@code CASE}. The grain
     * here is SKU x region x unit, which is a few hundred rows at most, so there is no window
     * function to partition and nothing to gain from pushing the logic down — while keeping it in
     * {@link GcpNetworkSku} makes it testable without BigQuery, which matters because the SKU
     * naming is the uncertain part.
     *
     * <p>{@code flows} is always empty and {@code resourceDataAvailable} always false: attributing
     * a charge to a named resource needs the detailed export
     * ({@code gcp_billing_export_resource_v1}), which nothing in the product records yet.
     */
    public DataTransferBreakdown getNetworkCosts(String gcpProjectId, YearMonth month,
                                                 boolean forceRefresh) {
        return cached(key("network", gcpProjectId, month),
                new TypeReference<DataTransferBreakdown>() {}, forceRefresh,
                () -> computeNetworkCosts(gcpProjectId, month),
                breakdown -> !breakdown.charges().isEmpty());
    }

    private DataTransferBreakdown computeNetworkCosts(String gcpProjectId, YearMonth month) {
        Source src = source(gcpProjectId).orElseThrow(() ->
                new IllegalStateException("No billing export for project " + gcpProjectId));

        String sql = "SELECT service.description AS service,"
                + " sku.description AS sku,"
                + " IFNULL(location.region, 'global') AS region,"
                + " IFNULL(usage.pricing_unit, '') AS unit,"
                + " SUM(CAST(IFNULL(usage.amount_in_pricing_units, 0) AS NUMERIC)) AS quantity,"
                // Aliased total_cost, not cost. `cost` is also a column of the export, and a
                // HAVING naming it would be ambiguous between the alias and the raw column — so
                // every query in GcpCostService aliases total_cost, and this follows.
                + " " + GcpBillingSql.NET_COST_SUM + " AS total_cost"
                + " FROM `" + src.table() + "`"
                + " WHERE " + GcpBillingSql.PROJECT_PREDICATE
                + " AND " + GcpBillingSql.USAGE_DATE_PREDICATE
                // The regex is a compile-time constant, not request input, so inlining it carries
                // no injection risk — and BigQuery wants a literal here.
                + " AND REGEXP_CONTAINS(LOWER(sku.description), r'" + GcpNetworkSku.SKU_FILTER_REGEX + "')"
                + " GROUP BY 1, 2, 3, 4"
                // Kept when the row had real GROSS cost, even where credits net it to zero — and
                // this differs from the AWS side deliberately. GCP's free tier and sustained-use
                // discounts arrive as credits against the very line they offset, so filtering on
                // net cost hides genuine traffic entirely: a project moving 800 GB inside the free
                // allowance shows no network rows at all, which is indistinguishable from a
                // project with no network activity. "You moved this much and paid nothing" is
                // worth saying, and it is the only way to tell those two states apart.
                + " HAVING SUM(CAST(cost AS NUMERIC)) > " + MIN_COST
                + "   OR total_cost > " + MIN_COST
                + " ORDER BY total_cost DESC";

        List<TransferCharge> charges = new ArrayList<>();
        boolean anyReportedRoute = false;
        for (FieldValueList row : run(src, sql, gcpProjectId, month, Map.of())) {
            String sku = string(row, "sku");
            String region = string(row, "region");
            GcpNetworkSku.Route route = GcpNetworkSku.routeOf(sku, region);
            if (route.isKnown() && !route.derived()) {
                anyReportedRoute = true;
            }
            charges.add(new TransferCharge(
                    GcpNetworkSku.categoryOf(sku),
                    blankTo(string(row, "service"), "Other"),
                    sku,
                    string(row, "unit"),
                    number(row, "quantity"),
                    number(row, "total_cost"),
                    blankTo(region, "global"),
                    route.from(),
                    route.to(),
                    route.derived()));
        }

        log.info("✅ [GCP] Network costs — {} charge rows for project {} ({}), reportedRoutes={}",
                charges.size(), gcpProjectId, month, anyReportedRoute);
        // True when at least one SKU named both endpoints itself. Unlike AWS this is not a
        // statement about which columns exist — there is no from/to column in the export at all —
        // but about whether Google spelled the route out, which is what the badge conveys.
        return new DataTransferBreakdown(charges, List.of(), anyReportedRoute, false);
    }

    // ── Effective savings rate ──────────────────────────────────────────────────

    /**
     * What the project paid against what the same usage would have cost at list price.
     *
     * <p>The GCP equivalent of the AWS effective savings rate, and it measures rather more:
     * sustained-use discounts, committed-use discounts, promotional credits and free tier all
     * arrive as credits, so the gap between list and net is the whole of the discount a customer
     * has earned — most of which they never had to negotiate.
     *
     * <p>Returns null when the export has no {@code cost_at_list} column, which older exports do
     * not. A rate computed without it would read as zero savings and be indistinguishable from a
     * customer genuinely paying list.
     */
    public SavingsRate getSavingsRate(String gcpProjectId, YearMonth month, boolean forceRefresh) {
        return cached(key("savings-rate", gcpProjectId, month),
                new TypeReference<SavingsRate>() {}, forceRefresh,
                () -> computeSavingsRate(gcpProjectId, month),
                rate -> rate.publicOnDemandCost() > 0);
    }

    private SavingsRate computeSavingsRate(String gcpProjectId, YearMonth month) {
        Source src = source(gcpProjectId).orElseThrow(() ->
                new IllegalStateException("No billing export for project " + gcpProjectId));
        if (!hasColumn(src, "cost_at_list")) {
            log.info("[GCP] Savings rate unavailable for project {} — export has no cost_at_list",
                    gcpProjectId);
            return null;
        }

        String sql = "SELECT " + GcpBillingSql.LIST_COST_SUM + " AS list_cost,"
                + " " + GcpBillingSql.NET_COST_SUM + " AS net_cost"
                + " FROM `" + src.table() + "`"
                + " WHERE " + GcpBillingSql.PROJECT_PREDICATE
                + " AND " + GcpBillingSql.USAGE_DATE_PREDICATE;

        for (FieldValueList row : run(src, sql, gcpProjectId, month, Map.of())) {
            double list = number(row, "list_cost");
            double net = number(row, "net_cost");
            double savings = list - net;
            // Guarded because a project with no usage has a zero denominator, and a project whose
            // credits exceed its list cost would otherwise report a rate above 1.
            double rate = list > 0 ? Math.max(0.0, Math.min(1.0, savings / list)) : 0.0;
            return new SavingsRate(net, list, savings, rate);
        }
        return new SavingsRate(0, 0, 0, 0);
    }

    // ── Charge type and credit breakdown ────────────────────────────────────────

    /**
     * Spend split by what kind of charge it is, and discounts split by what kind of credit.
     *
     * <p>The usual answer to "why doesn't this match my invoice": the gap is tax, an adjustment,
     * or a credit, and until now nothing on the GCP page could show which. Credits come out
     * negative, as they do in the export, so the rows sum to the invoice figure.
     */
    public List<ChargeTypeAmount> getChargeTypeBreakdown(String gcpProjectId, YearMonth month,
                                                         boolean forceRefresh) {
        return cached(key("charge-types", gcpProjectId, month),
                new TypeReference<List<ChargeTypeAmount>>() {}, forceRefresh,
                () -> computeChargeTypeBreakdown(gcpProjectId, month),
                rows -> !rows.isEmpty());
    }

    private List<ChargeTypeAmount> computeChargeTypeBreakdown(String gcpProjectId, YearMonth month) {
        Source src = source(gcpProjectId).orElseThrow(() ->
                new IllegalStateException("No billing export for project " + gcpProjectId));

        String costTypeColumn = hasColumn(src, "cost_type") ? "cost_type" : null;
        String chargeSide = costTypeColumn == null
                ? "SELECT 'Usage' AS charge_type, SUM(CAST(cost AS NUMERIC)) AS amount"
                : "SELECT IFNULL(NULLIF(cost_type, ''), 'regular') AS charge_type,"
                        + " SUM(CAST(cost AS NUMERIC)) AS amount";

        // The cross join is correct here, unlike in the label queries: this half sums only
        // c.amount, so fanning the row out once per credit is exactly what is wanted and there is
        // no SUM(cost) alongside it to be multiplied. See GcpBillingSql.labelValueExpr for the
        // case where the same construct is a bug.
        String sql = chargeSide
                + " FROM `" + src.table() + "`"
                + " WHERE " + GcpBillingSql.PROJECT_PREDICATE
                + " AND " + GcpBillingSql.USAGE_DATE_PREDICATE
                + " GROUP BY 1"
                + " UNION ALL"
                + " SELECT IFNULL(NULLIF(c.type, ''), 'CREDIT') AS charge_type,"
                + " SUM(CAST(c.amount AS NUMERIC)) AS amount"
                + " FROM `" + src.table() + "`, UNNEST(credits) AS c"
                + " WHERE " + GcpBillingSql.PROJECT_PREDICATE
                + " AND " + GcpBillingSql.USAGE_DATE_PREDICATE
                + " GROUP BY 1";

        List<ChargeTypeAmount> result = new ArrayList<>();
        for (FieldValueList row : run(src, sql, gcpProjectId, month, Map.of())) {
            double amount = number(row, "amount");
            if (Math.abs(amount) < 0.001) {
                continue;
            }
            result.add(new ChargeTypeAmount(readable(string(row, "charge_type")), amount));
        }
        result.sort((a, b) -> Double.compare(Math.abs(b.cost()), Math.abs(a.cost())));
        return result;
    }

    // ── Charges by service, at invoice grain ────────────────────────────────────

    /**
     * Every billed line as the invoice itself breaks it down: service, region, SKU, unit, quantity.
     *
     * <p>Distinct from the existing cost breakdown, which returns one total per service. That
     * answers "Compute Engine cost ₹2,314" and stops; this answers "of which ₹1,900 is N2 instance
     * core hours in Mumbai and ₹114 is a persistent disk". The service total is the number people
     * notice and the SKU line is the number they can act on, and only {@code sku.description} can
     * supply the second — which nothing in the product read before.
     *
     * <p>{@code HAVING ABS(...)} rather than {@code > 0}: a credit or an adjustment is negative and
     * belongs on an invoice view, so filtering on the signed value would drop exactly the rows that
     * explain why the total is lower than the usage suggests.
     */
    public List<ServiceCharge> getChargesByService(String gcpProjectId, YearMonth month,
                                                   boolean forceRefresh) {
        return cached(key("charges-by-service", gcpProjectId, month),
                new TypeReference<List<ServiceCharge>>() {}, forceRefresh,
                () -> computeChargesByService(gcpProjectId, month),
                rows -> !rows.isEmpty());
    }

    private List<ServiceCharge> computeChargesByService(String gcpProjectId, YearMonth month) {
        Source src = source(gcpProjectId).orElseThrow(() ->
                new IllegalStateException("No billing export for project " + gcpProjectId));

        String sql = "SELECT service.description AS service,"
                + " IFNULL(location.region, 'global') AS region,"
                + " sku.description AS description,"
                // The SKU id is GCP's equivalent of an AWS usage type: the stable code behind the
                // human description, and what a customer quotes to support.
                + " IFNULL(sku.id, '') AS usage_type,"
                + " IFNULL(usage.pricing_unit, '') AS unit,"
                + " SUM(CAST(IFNULL(usage.amount_in_pricing_units, 0) AS NUMERIC)) AS quantity,"
                + " " + GcpBillingSql.NET_COST_SUM + " AS total_cost"
                + " FROM `" + src.table() + "`"
                + " WHERE " + GcpBillingSql.PROJECT_PREDICATE
                + " AND " + GcpBillingSql.USAGE_DATE_PREDICATE
                + " GROUP BY 1, 2, 3, 4, 5"
                + " HAVING ABS(total_cost) > " + MIN_COST
                + " ORDER BY total_cost DESC";

        List<ServiceCharge> charges = new ArrayList<>();
        for (FieldValueList row : run(src, sql, gcpProjectId, month, Map.of())) {
            String description = string(row, "description");
            charges.add(new ServiceCharge(
                    blankTo(string(row, "service"), "Other"),
                    blankTo(string(row, "region"), "global"),
                    // Description is blank on a few line types; the SKU id is the next most
                    // specific thing a reader can act on, matching the AWS fallback.
                    blankTo(description, blankTo(string(row, "usage_type"), "—")),
                    string(row, "usage_type"),
                    string(row, "unit"),
                    number(row, "quantity"),
                    number(row, "total_cost")));
        }
        log.info("✅ [GCP] Charges by service — {} rows for project {} ({})",
                charges.size(), gcpProjectId, month);
        return charges;
    }

    // ── Charges by resource ─────────────────────────────────────────────────────

    /**
     * What each individual resource cost — the instance, the disk, the bucket.
     *
     * <p>Requires GCP's <em>detailed</em> billing export. The standard export carries no
     * {@code resource} field at all, so this returns null there and the caller reports it as
     * unavailable rather than as an account with no resources. Detection is a metadata probe on the
     * table, not a stored setting: the export can be switched at any time and the schema is the
     * only thing that knows the answer at the moment of reading.
     *
     * <p>The windows are computed over every resource and before the limit, so the caller can say
     * "top 500 of 3,214, ₹x in total" honestly rather than summing the returned page.
     */
    public TopResources getTopResources(String gcpProjectId, YearMonth month, boolean forceRefresh) {
        return cached(key("top-resources", gcpProjectId, month),
                new TypeReference<TopResources>() {}, forceRefresh,
                () -> computeTopResources(gcpProjectId, month),
                top -> top != null && !top.resources().isEmpty());
    }

    private TopResources computeTopResources(String gcpProjectId, YearMonth month) {
        Source src = source(gcpProjectId).orElseThrow(() ->
                new IllegalStateException("No billing export for project " + gcpProjectId));
        if (!hasColumn(src, "resource")) {
            log.info("[GCP] Per-resource cost unavailable for project {} — this is the standard"
                    + " export, which records no resource name", gcpProjectId);
            return null;
        }

        String sql = "SELECT resource_id, resource_name, service, region, total_cost,"
                + " SUM(total_cost) OVER () AS account_total,"
                + " COUNT(*) OVER () AS group_count"
                + " FROM ("
                // global_name is the stable, globally unique identifier; name is the shorter form
                // the owning service uses. Preferring global_name keeps two same-named resources in
                // different zones apart, which `name` alone does not guarantee.
                + "SELECT IFNULL(NULLIF(resource.global_name, ''), IFNULL(resource.name, '')) AS resource_id,"
                + " IFNULL(resource.name, '') AS resource_name,"
                + " service.description AS service,"
                + " IFNULL(location.region, 'global') AS region,"
                + " " + GcpBillingSql.NET_COST_SUM + " AS total_cost"
                + " FROM `" + src.table() + "`"
                + " WHERE " + GcpBillingSql.PROJECT_PREDICATE
                + " AND " + GcpBillingSql.USAGE_DATE_PREDICATE
                + " GROUP BY 1, 2, 3, 4"
                + ")"
                + " WHERE ABS(total_cost) > " + MIN_COST
                + " ORDER BY total_cost DESC"
                + " LIMIT " + MAX_RESOURCES;

        List<ResourceCost> resources = new ArrayList<>();
        double totalCost = 0;
        long groupCount = 0;
        for (FieldValueList row : run(src, sql, gcpProjectId, month, Map.of())) {
            resources.add(new ResourceCost(
                    string(row, "resource_id"),
                    shortResourceName(string(row, "resource_name")),
                    blankTo(string(row, "service"), "Other"),
                    blankTo(string(row, "region"), "global"),
                    // GCP records the machine spec in system_labels, a repeated field this query
                    // does not join. Left empty rather than half-filled; the AWS component treats
                    // an absent instance type as absent, not as an error.
                    "",
                    number(row, "total_cost")));
            // Identical on every row — the windows are unpartitioned.
            totalCost = number(row, "account_total");
            groupCount = (long) number(row, "group_count");
        }
        log.info("✅ [GCP] Top resources — {} of {} rows for project {} ({})",
                resources.size(), groupCount, gcpProjectId, month);
        return new TopResources(resources, totalCost, groupCount);
    }

    /**
     * The readable tail of a GCP resource name.
     *
     * <p>A detailed-export resource name is often a full path
     * ({@code //compute.googleapis.com/projects/p/zones/z/instances/web-1}), whose distinguishing
     * part is at the end. An untruncated column of these is a wall of identical prefixes.
     */
    private static String shortResourceName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        int lastSlash = name.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash < name.length() - 1 ? name.substring(lastSlash + 1) : name;
    }

    // ── One resource, in depth ──────────────────────────────────────────────────

    /**
     * Everything the resource drill-down needs for one resource.
     *
     * <p>Null on the standard billing export, which records no {@code resource} field at all — the same
     * condition that makes the resource table unavailable, so a reader who can see the table can open
     * the panel and one who cannot never reaches it. Detected by probing the table's schema rather than
     * by reading a stored setting, because the export can be switched at any time and the schema is the
     * only thing that knows the answer at the moment of reading.
     *
     * <p>One scan for both months' SKUs with a period label, and a second for the daily series. The
     * resource predicate makes both narrow regardless of how large the project is, which is what makes
     * it safe to run on a click.
     */
    public ResourceChange getResourceChange(String gcpProjectId, String resourceId, YearMonth month,
                                            boolean forceRefresh) {
        return cached("gcp:cost-insight:resource-change:" + gcpProjectId + ":" + resourceId + ":" + month,
                new TypeReference<ResourceChange>() {}, forceRefresh,
                () -> computeResourceChange(gcpProjectId, resourceId, month),
                change -> change != null);
    }

    private ResourceChange computeResourceChange(String gcpProjectId, String resourceId,
                                                 YearMonth month) {
        Source src = source(gcpProjectId).orElseThrow(() ->
                new IllegalStateException("No billing export for project " + gcpProjectId));
        if (!hasColumn(src, "resource")) {
            log.info("[GCP] Resource drill-down unavailable for project {} — this is the standard"
                    + " export, which records no resource name", gcpProjectId);
            return null;
        }

        YearMonth prior = month.minusMonths(1);
        String resourceExpr = "IFNULL(NULLIF(resource.global_name, ''), IFNULL(resource.name, ''))";

        String sql = "SELECT sku.description AS description,"
                + " IFNULL(sku.id, '') AS usage_type,"
                + " IFNULL(usage.pricing_unit, '') AS unit,"
                + " service.description AS service,"
                + " IFNULL(location.region, 'global') AS region,"
                + " IFNULL(resource.name, '') AS resource_name,"
                + " IF(DATE(usage_start_time) >= @currentStart, 'current', 'prior') AS period,"
                + " " + GcpBillingSql.NET_COST_SUM + " AS total_cost,"
                + " SUM(CAST(IFNULL(usage.amount_in_pricing_units, 0) AS NUMERIC)) AS quantity"
                + " FROM `" + src.table() + "`"
                + " WHERE " + GcpBillingSql.PROJECT_PREDICATE
                + " AND " + GcpBillingSql.USAGE_DATE_PREDICATE
                + " AND " + resourceExpr + " = @resourceId"
                + " GROUP BY 1, 2, 3, 4, 5, 6, 7";

        QueryJobConfiguration config = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("projectId", QueryParameterValue.string(gcpProjectId))
                .addNamedParameter("resourceId", QueryParameterValue.string(resourceId))
                .addNamedParameter("startDate", date(prior.atDay(1)))
                .addNamedParameter("endDate", date(month.atEndOfMonth()))
                .addNamedParameter("currentStart", date(month.atDay(1)))
                .build();

        // [priorCost, currentCost, priorQty, currentQty]
        Map<String, double[]> bySku = new LinkedHashMap<>();
        Map<String, String[]> descriptors = new LinkedHashMap<>();
        String service = "";
        String region = "global";
        String name = "";

        for (FieldValueList row : query(src, config, gcpProjectId)) {
            String usageType = string(row, "usage_type");
            String description = blankTo(string(row, "description"), blankTo(usageType, "—"));
            // Keyed on the SKU id where there is one, since two SKUs can share a description.
            String key = blankTo(usageType, description);
            boolean isCurrent = "current".equals(string(row, "period"));

            double[] slot = bySku.computeIfAbsent(key, k -> new double[4]);
            slot[isCurrent ? 1 : 0] += number(row, "total_cost");
            slot[isCurrent ? 3 : 2] += number(row, "quantity");
            descriptors.put(key, new String[]{description, string(row, "unit")});

            // Descriptors from whichever month has rows, so a resource deleted mid-comparison — the one
            // a reader most wants to open — still names itself.
            if (service.isBlank()) service = string(row, "service");
            if (name.isBlank()) name = shortResourceName(string(row, "resource_name"));
            String rowRegion = string(row, "region");
            if (!rowRegion.isBlank()) region = rowRegion;
        }

        List<ResourceLineChange> lines = new ArrayList<>();
        double priorCost = 0;
        double currentCost = 0;
        double priorQty = 0;
        double currentQty = 0;

        for (Map.Entry<String, double[]> entry : bySku.entrySet()) {
            double[] s = entry.getValue();
            priorCost += s[0];
            currentCost += s[1];
            priorQty += s[2];
            currentQty += s[3];

            // Per SKU the split is exact rather than indicative: one SKU bills in one pricing unit, so
            // cost ÷ quantity is a real unit rate. The service-level split blends units across a
            // service's SKUs and can only point at a direction — this is the panel's depth over the table.
            PriceVolumeSplit.Effects effects = PriceVolumeSplit.of(s[0], s[1], s[2], s[3]);
            String[] descriptor = descriptors.getOrDefault(entry.getKey(), new String[]{"", ""});
            lines.add(new ResourceLineChange(descriptor[0], entry.getKey(), descriptor[1],
                    s[0], s[1], s[1] - s[0], s[2], s[3],
                    effects.priceEffect(), effects.volumeEffect()));
        }
        lines.sort(Comparator.comparingDouble((ResourceLineChange l) -> -Math.abs(l.costChange())));

        PriceVolumeSplit.Effects total =
                PriceVolumeSplit.of(priorCost, currentCost, priorQty, currentQty);

        return new ResourceChange(resourceId,
                blankTo(name, shortResourceName(resourceId)),
                blankTo(service, "Other"), region,
                // GCP records the machine spec in system_labels, a repeated field this query does not
                // join. Left empty rather than half-filled, matching the resource table.
                "",
                priorCost, currentCost, currentCost - priorCost,
                priorQty, currentQty,
                total.priceEffect(), total.volumeEffect(),
                // Any prior-side row at all means the export reaches that month. All-zero across every
                // line means it does not, and reporting that as a real earlier month of zero spend would
                // make the resource look newly created when the export is simply younger than it.
                priorCost != 0 || priorQty != 0,
                List.copyOf(lines),
                dailyForResource(src, gcpProjectId, resourceId, month, resourceExpr));
    }

    /**
     * One resource's cost per day over the month.
     *
     * <p>Every cost type, unlike the comparison above — this is the resource's spend, and a reader
     * looking at the shape of a change wants the day a charge landed whether or not it carried a
     * pricing unit.
     */
    private List<DailyCost> dailyForResource(Source src, String gcpProjectId, String resourceId,
                                             YearMonth month, String resourceExpr) {
        String sql = "SELECT DATE(usage_start_time) AS day,"
                + " " + GcpBillingSql.NET_COST_SUM + " AS total_cost"
                + " FROM `" + src.table() + "`"
                + " WHERE " + GcpBillingSql.PROJECT_PREDICATE
                + " AND " + GcpBillingSql.USAGE_DATE_PREDICATE
                + " AND " + resourceExpr + " = @resourceId"
                + " GROUP BY 1 ORDER BY 1";

        QueryJobConfiguration config = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("projectId", QueryParameterValue.string(gcpProjectId))
                .addNamedParameter("resourceId", QueryParameterValue.string(resourceId))
                .addNamedParameter("startDate", date(month.atDay(1)))
                .addNamedParameter("endDate", date(month.atEndOfMonth()))
                .build();

        List<DailyCost> daily = new ArrayList<>();
        try {
            for (FieldValueList row : query(src, config, gcpProjectId)) {
                String day = string(row, "day");
                if (!day.isBlank()) {
                    daily.add(new DailyCost(day, number(row, "total_cost")));
                }
            }
        } catch (Exception e) {
            // The panel's other two tabs are already in hand; a missing trend costs one chart.
            log.debug("[GCP] Daily series unavailable for {} / {}: {}", gcpProjectId, resourceId,
                    e.getMessage());
            return List.of();
        }
        return List.copyOf(daily);
    }

    // ── Price vs volume ─────────────────────────────────────────────────────────

    /**
     * Why spend changed: how much of each service's movement was rate, and how much was usage.
     *
     * <p>One scan covering both months with a period label, rather than two queries, because the
     * two figures are only meaningful side by side.
     *
     * <p>The same caveat applies as on the AWS side: quantity is summed across a service's SKUs,
     * which mix units — gibibytes with hours with requests — so the split is a strong indicator
     * rather than an exact attribution. A service billed in one unit reads exactly; a broad one
     * reads directionally.
     */
    public List<PriceVolumeChange> getPriceVsVolume(String gcpProjectId, YearMonth current,
                                                    boolean forceRefresh) {
        return cached(key("price-vs-volume", gcpProjectId, current),
                new TypeReference<List<PriceVolumeChange>>() {}, forceRefresh,
                () -> computePriceVsVolume(gcpProjectId, current),
                rows -> !rows.isEmpty());
    }

    private List<PriceVolumeChange> computePriceVsVolume(String gcpProjectId, YearMonth current) {
        Source src = source(gcpProjectId).orElseThrow(() ->
                new IllegalStateException("No billing export for project " + gcpProjectId));
        YearMonth prior = current.minusMonths(1);

        String sql = "SELECT service.description AS service,"
                + " IF(DATE(usage_start_time) >= @currentStart, 'current', 'prior') AS period,"
                // total_cost rather than cost, for the same name-collision reason as above.
                + " " + GcpBillingSql.NET_COST_SUM + " AS total_cost,"
                + " SUM(CAST(IFNULL(usage.amount_in_pricing_units, 0) AS NUMERIC)) AS quantity"
                + " FROM `" + src.table() + "`"
                + " WHERE " + GcpBillingSql.PROJECT_PREDICATE
                + " AND " + GcpBillingSql.USAGE_DATE_PREDICATE
                + " GROUP BY 1, 2";

        Map<String, double[]> byService = new LinkedHashMap<>();
        QueryJobConfiguration config = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("projectId", QueryParameterValue.string(gcpProjectId))
                .addNamedParameter("startDate", date(prior.atDay(1)))
                .addNamedParameter("endDate", date(current.atEndOfMonth()))
                .addNamedParameter("currentStart", date(current.atDay(1)))
                .build();

        for (FieldValueList row : query(src, config, gcpProjectId)) {
            String service = blankTo(string(row, "service"), "Other");
            // [priorCost, currentCost, priorQty, currentQty]
            double[] slot = byService.computeIfAbsent(service, k -> new double[4]);
            boolean isCurrent = "current".equals(string(row, "period"));
            slot[isCurrent ? 1 : 0] = number(row, "total_cost");
            slot[isCurrent ? 3 : 2] = number(row, "quantity");
        }

        List<PriceVolumeChange> changes = new ArrayList<>();
        byService.forEach((service, s) -> {
            double priorCost = s[0];
            double currentCost = s[1];
            double priorQty = s[2];
            double currentQty = s[3];
            // Shared with the AWS and Azure paths and with the per-resource breakdown, so all four report
            // the same split — including the new-service case this copy used to get backwards. See
            // PriceVolumeSplit for the decomposition and why price is taken as the residual.
            PriceVolumeSplit.Effects effects =
                    PriceVolumeSplit.of(priorCost, currentCost, priorQty, currentQty);
            changes.add(new PriceVolumeChange(service, priorCost, currentCost,
                    currentCost - priorCost, priorQty, currentQty,
                    effects.priceEffect(), effects.volumeEffect()));
        });
        changes.sort((a, b) -> Double.compare(Math.abs(b.costChange()), Math.abs(a.costChange())));
        return changes;
    }

    // ── Query plumbing ──────────────────────────────────────────────────────────

    /** A project's billing export: a client authorised for it and the table to read. */
    private record Source(BigQuery client, String table) {
    }

    private Optional<Source> source(String gcpProjectId) {
        try {
            Optional<BigQuery> client = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (client.isEmpty()) {
                return Optional.empty();
            }
            return billingStatusService.resolveExportTable(client.get(), gcpProjectId)
                    .map(table -> new Source(client.get(), table));
        } catch (Exception e) {
            log.warn("[GCP] Could not resolve a billing export for project {}: {}",
                    gcpProjectId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Runs a query scoped to one project and one month. */
    private Iterable<FieldValueList> run(Source src, String sql, String gcpProjectId,
                                         YearMonth month, Map<String, String> extra) {
        QueryJobConfiguration.Builder builder = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("projectId", QueryParameterValue.string(gcpProjectId))
                .addNamedParameter("startDate", date(month.atDay(1)))
                .addNamedParameter("endDate", date(month.atEndOfMonth()));
        extra.forEach((name, value) ->
                builder.addNamedParameter(name, QueryParameterValue.string(value)));
        return query(src, builder.build(), gcpProjectId);
    }

    private Iterable<FieldValueList> query(Source src, QueryJobConfiguration config,
                                           String gcpProjectId) {
        try {
            TableResult result = src.client().query(config);
            return result.iterateAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("BigQuery query interrupted for " + gcpProjectId, e);
        } catch (Exception e) {
            // Thrown rather than swallowed to an empty list: the caller turns this into an
            // explicit failure for the UI, where the existing cost path's habit of returning
            // nothing made a broken query indistinguishable from zero spend.
            throw new IllegalStateException(
                    "BigQuery query failed for project " + gcpProjectId + ": " + e.getMessage(), e);
        }
    }

    /**
     * The month as a BigQuery DATE parameter. The end of the requested month is used whole and is
     * deliberately not clamped to today, matching the AWS side: reservation and subscription rows
     * carry usage dates spanning the whole period as soon as it opens, and they are already billed.
     */
    private static QueryParameterValue date(LocalDate day) {
        return QueryParameterValue.date(day.format(DateTimeFormatter.ISO_LOCAL_DATE));
    }

    // ── Schema probe ────────────────────────────────────────────────────────────

    private record CachedSchema(Set<String> columns, Instant expiresAt) {
    }

    /**
     * Whether the export carries a column.
     *
     * <p>Metadata only, so it scans no data and costs nothing beyond a round trip. Necessary
     * because BigQuery rejects the whole query when a selected column is absent, so one missing
     * column would take a section down on exactly the accounts whose export predates it.
     *
     * <p>Unreadable metadata is reported as "column absent", which degrades a section rather than
     * failing it.
     */
    private boolean hasColumn(Source src, String column) {
        CachedSchema hit = schemaCache.get(src.table());
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.columns().contains(column);
        }
        Set<String> columns = new HashSet<>();
        try {
            String[] parts = src.table().split("\\.");
            Table table = src.client().getTable(TableId.of(parts[0], parts[1], parts[2]));
            TableDefinition definition = table == null ? null : table.getDefinition();
            Schema schema = definition instanceof StandardTableDefinition standard
                    ? standard.getSchema() : null;
            if (schema != null) {
                for (Field field : schema.getFields()) {
                    columns.add(field.getName());
                }
            }
        } catch (Exception e) {
            log.warn("[GCP] Could not read the schema of {} — optional columns will be treated as"
                    + " absent: {}", src.table(), e.getMessage());
        }
        schemaCache.put(src.table(), new CachedSchema(columns, Instant.now().plus(SCHEMA_TTL)));
        return columns.contains(column);
    }

    // ── Row helpers ─────────────────────────────────────────────────────────────

    private static String string(FieldValueList row, String field) {
        return row.get(field).isNull() ? "" : row.get(field).getStringValue();
    }

    private static double number(FieldValueList row, String field) {
        if (row.get(field).isNull()) {
            return 0.0;
        }
        // NUMERIC arrives as a BigDecimal-backed value; asking for a double directly throws on it.
        return row.get(field).getNumericValue().doubleValue();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** "COMMITTED_USAGE_DISCOUNT" reads as "Committed usage discount". */
    private static String readable(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Unknown";
        }
        String spaced = raw.replace('_', ' ').trim().toLowerCase();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    // ── Results, field-for-field identical to the AWS records ───────────────────

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.DataTransferBreakdown */
    public record DataTransferBreakdown(List<TransferCharge> charges,
                                        List<TransferFlow> flows,
                                        boolean routeDataAvailable,
                                        boolean resourceDataAvailable) {
    }

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.TransferCharge */
    public record TransferCharge(String category, String service, String usageType,
                                 String unit, double quantity, double cost,
                                 String region, String fromLocation, String toLocation,
                                 boolean routeDerived) {
    }

    /**
     * Present so the payload shape matches AWS exactly; always empty until the detailed export is
     * supported, which is what carries {@code resource.name}.
     *
     * @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.TransferFlow
     */
    public record TransferFlow(String category, String resourceId, String name,
                               String service, String region, String availabilityZone,
                               String fromLocation, String toLocation, boolean routeDerived,
                               String usageType,
                               double gigabytes, double hours, double cost,
                               double categoryCost, long categoryFlows) {
    }

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.SavingsRate */
    public record SavingsRate(double amortizedCost, double publicOnDemandCost,
                              double savings, double rate) {
    }

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.ChargeTypeAmount */
    public record ChargeTypeAmount(String chargeType, double cost) {
    }

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.ServiceCharge */
    public record ServiceCharge(String service, String region, String description,
                                String usageType, String unit, double quantity, double cost) {
    }

    /**
     * @param instanceType always empty on GCP today — the machine spec lives in
     *                     {@code system_labels}, a repeated field this query does not join
     * @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.ResourceCost
     */
    public record ResourceCost(String resourceId, String name, String service, String region,
                               String instanceType, double cost) {
    }

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.TopResources */
    public record TopResources(List<ResourceCost> resources, double totalCost, long groupCount) {
    }

    /**
     * @param instanceType always empty on GCP, matching {@link ResourceCost} — the machine spec lives in
     *                     {@code system_labels}, a repeated field these queries do not join
     * @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.ResourceChange
     */
    public record ResourceChange(String resourceId, String name, String service, String region,
                                 String instanceType,
                                 double priorCost, double currentCost, double costChange,
                                 double priorQuantity, double currentQuantity,
                                 double priceEffect, double volumeEffect,
                                 /**
                                  * False when the export does not reach the earlier month. A GCP billing
                                  * export, like a CUR, begins when it was enabled and does not backfill —
                                  * so a recently enabled one can describe this month and have nothing to
                                  * compare it against. Derived from the rows rather than probed: the
                                  * single scan covers both months, so an entirely absent prior side is
                                  * itself the answer.
                                  */
                                 boolean comparisonAvailable,
                                 List<ResourceLineChange> lines,
                                 List<DailyCost> daily) {
    }

    /**
     * @param usageType the SKU id, GCP's equivalent of an AWS usage type
     * @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.ResourceLineChange
     */
    public record ResourceLineChange(String description, String usageType, String unit,
                                     double priorCost, double currentCost, double costChange,
                                     double priorQuantity, double currentQuantity,
                                     double priceEffect, double volumeEffect) {
    }

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.DailyCost */
    public record DailyCost(String date, double cost) {
    }

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.PriceVolumeChange */
    public record PriceVolumeChange(String service, double priorCost, double currentCost,
                                    double costChange, double priorQuantity, double currentQuantity,
                                    double priceEffect, double volumeEffect) {
    }
}
