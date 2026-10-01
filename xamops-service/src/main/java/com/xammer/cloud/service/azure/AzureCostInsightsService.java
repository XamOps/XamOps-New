package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.azure.AzureCostQuery.Grouping;
import com.xammer.cloud.service.azure.AzureCostQuery.Request;
import com.xammer.cloud.service.azure.AzureCostQuery.Result;
import com.xammer.cloud.service.azure.AzureCostQuery.Row;
import com.xammer.cloud.util.PriceVolumeSplit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The Cost Management dimensions the Azure cost page never read.
 *
 * <p>{@link AzureCostQueryService} answers "what did this subscription spend, by meter category and
 * location" — two dimensions, one aggregation — and every Azure cost surface is built on that. It is
 * the same ceiling the AWS pages sat under before the Cost and Usage Report work: it says what was
 * billed and nothing about why. This class is the Azure counterpart of
 * {@code CurAdvancedMetricsService} and {@code GcpCostInsightsService}, and reads the rest of what
 * Cost Management will give up — {@code ResourceId}, {@code Meter}, {@code MeterSubCategory},
 * {@code UnitOfMeasure}, {@code ResourceGroupName}, {@code ChargeType}, {@code UsageQuantity} and
 * tag values.
 *
 * <h2>The DTOs are deliberately identical to the AWS ones</h2>
 * Field for field, name for name, exactly as the GCP service does. The Cost page's invoice, resource,
 * network and price-vs-volume sections are already built, tested and understood, and they already
 * take a fetcher as a prop — so making the Azure payload the same shape means they render Azure data
 * with no second implementation to keep in step. A parallel set of components would compile today and
 * drift the first time one side gained a column.
 *
 * <h2>Why every query is a ladder rather than a statement</h2>
 * Which dimensions a scope will group by depends on the agreement behind it — EA, MCA and
 * pay-as-you-go differ — and Cost Management reports an unsupported one as a 400, or as an empty row
 * set, rather than by omitting the column. There is no catalogue to consult, so each section states
 * what it wants as an ordered list of grouping attempts and takes the first that answers. What was
 * actually obtained is then read back off the result, so a record carries a blank where a column was
 * never returned instead of a value invented to fill it. {@link AzureCostQuery} holds that
 * machinery.
 *
 * <h2>Where this cannot answer</h2>
 * Reported rather than returned as zero, because an empty section and an unavailable one look
 * identical on screen and need opposite things said about them:
 *
 * <ul>
 *   <li><b>No Cost Management access.</b> The service principal needs <em>Cost Management Reader</em>
 *       on the subscription. Without it every query is a 403, which {@link AzureArmClient} raises
 *       rather than flattening to empty — so the caller reports a fault instead of an account that
 *       spent nothing.</li>
 *   <li><b>Tenant policy.</b> An Azure tenant can restrict cost data to billing administrators, in
 *       which case the role assignment is present and the data still is not.</li>
 *   <li><b>Scopes that will not group by resource.</b> Per-resource cost is the one section with no
 *       usable degraded form, so it reports as unavailable rather than showing meter categories under
 *       a heading that promises resources.</li>
 * </ul>
 */
@Service
@Slf4j
public class AzureCostInsightsService {

    /**
     * Rows below this round to zero on screen.
     *
     * <p>Filtered on the absolute value, not the signed one: a credit, a refund and a rounding
     * adjustment are negative and belong on an invoice view, so dropping everything under zero would
     * remove exactly the rows that explain why a total is lower than the usage suggests.
     */
    private static final double MIN_COST = 0.005;

    /**
     * Ceiling on resources returned in one response.
     *
     * <p>Grouping by resource id is by far the widest aggregation here and every row crosses the wire
     * and renders. Ordered by cost, the tail beyond this is rounding, and the true total and count
     * travel separately so nothing is misreported. Same figure as the AWS and GCP sides.
     */
    private static final int MAX_RESOURCES = 500;

    /** How many services the change comparison lists before it stops being a summary. */
    private static final int MAX_PRICE_VOLUME_SERVICES = 40;

    /**
     * How long a section is served from Redis.
     *
     * <p>Not a convenience. Cost Management is the most aggressively throttled endpoint in the Azure
     * integration — {@link AzureArmClient} carries a dedicated retry path for its rate-limit headers
     * — and these sections issue several queries each. Uncached, opening the Cost page would spend a
     * subscription's whole query budget on figures Azure itself only rebuilds every four hours. The
     * same reasoning produced {@code CurInsightCache} on the AWS side.
     */
    private static final int SECTION_TTL_MINUTES = 6 * 60;

    /**
     * How long an empty or failed section is remembered. Deliberately short: an empty result is more
     * often a throttle that got through than a real absence of spend, and caching one for hours turns
     * a blip into the day's answer.
     */
    private static final int EMPTY_TTL_MINUTES = 10;

    private final AzureArmClient armClient;
    private final CloudAccountRepository cloudAccountRepository;
    private final RedisCacheService redisCacheService;
    private final AzureBillingDataIngestionService ingestionService;
    private final ObjectMapper objectMapper;
    private final AzureCostQuery query;

    public AzureCostInsightsService(AzureArmClient armClient,
                                    CloudAccountRepository cloudAccountRepository,
                                    RedisCacheService redisCacheService,
                                    AzureBillingDataIngestionService ingestionService,
                                    ObjectMapper objectMapper) {
        this.armClient = armClient;
        this.cloudAccountRepository = cloudAccountRepository;
        this.redisCacheService = redisCacheService;
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
        this.query = new AzureCostQuery(armClient, objectMapper);
    }

    // ── Availability ────────────────────────────────────────────────────────────

    /**
     * Why these sections cannot be shown for a subscription, or empty when they can.
     *
     * <p>Checked before any query so the caller can answer "not available here" without spending a
     * Cost Management call, and so the UI can hide the sections rather than draw empty ones.
     */
    public Optional<String> unavailableReason(String subscriptionId) {
        Optional<CloudAccount> account = cloudAccountRepository.findByAzureSubscriptionId(subscriptionId);
        if (account.isEmpty()) {
            return Optional.of("No Azure subscription with this id is connected to XamOps.");
        }
        if (account.get().getAzureSubscriptionId() == null
                || account.get().getAzureSubscriptionId().isBlank()) {
            return Optional.of("This account has no Azure subscription id recorded, so Cost"
                    + " Management cannot be queried for it.");
        }
        return Optional.empty();
    }

    /**
     * The currency every figure from this service is denominated in.
     *
     * <p>Read from the ingestion caches, which record what Cost Management reported alongside the
     * costs, and defaulted to USD only when nothing has been ingested yet. Exposed because the Azure
     * cost components hardcoded a rupee symbol — correct for an INR-billed subscription and wrong by
     * roughly ninety-fold for any other, which on a cost page is the whole point of the number.
     */
    public String currencyOf(String subscriptionId) {
        return ingestionService.readCurrency(subscriptionId).filter(c -> !c.isBlank()).orElse("USD");
    }

    // ── Charges by service ──────────────────────────────────────────────────────

    /**
     * Every billed line at invoice grain: service, region, meter, unit, quantity, cost.
     *
     * <p>The breakdown section above it stops at "Virtual Machines — ₹2,314". This says which meter
     * that was, how much of it was used and at what unit — the level an invoice is reconciled at and
     * the level a rate change is visible at.
     *
     * <p>Azure spreads what AWS puts in one usage type across three columns, so the mapping is:
     * {@code MeterCategory} is the service, {@code Meter} is the description a reader recognises, and
     * {@code MeterSubCategory} is the stable classification behind it — the nearest thing to an AWS
     * usage type. Quantity is absent on scopes that will not aggregate {@code UsageQuantity}, in
     * which case the column reads zero rather than the section failing.
     */
    public List<ServiceCharge> getChargesByService(String subscriptionId, YearMonth month,
                                                  boolean forceRefresh) {
        return cached(key("charges-by-service", subscriptionId, month),
                new TypeReference<List<ServiceCharge>>() {}, forceRefresh,
                () -> toCharges(invoiceGrain(subscriptionId, month)),
                rows -> !rows.isEmpty());
    }

    /**
     * The widest grouping the scope will accept for an invoice view.
     *
     * <p>Shared with the network section, which needs the same columns and then filters them — so on
     * a cached page the two cost one query between them rather than two.
     */
    private Result invoiceGrain(String subscriptionId, YearMonth month) {
        return run(subscriptionId, new Request(
                month.atDay(1), endOf(month), "ActualCost",
                List.of(AzureCostQuery.COST, AzureCostQuery.QUANTITY),
                List.of(
                        List.of(dim("MeterCategory"), dim("MeterSubCategory"), dim("Meter"),
                                dim("ResourceLocation"), dim("UnitOfMeasure")),
                        List.of(dim("MeterCategory"), dim("MeterSubCategory"), dim("Meter"),
                                dim("ResourceLocation")),
                        List.of(dim("MeterCategory"), dim("Meter"), dim("ResourceLocation")),
                        List.of(dim("MeterCategory"), dim("Meter")),
                        List.of(dim("MeterCategory"), dim("ResourceLocation")),
                        List.of(dim("MeterCategory")))));
    }

    private List<ServiceCharge> toCharges(Result result) {
        List<ServiceCharge> charges = new ArrayList<>();
        for (Row row : result.rows()) {
            double cost = row.measure(AzureCostQuery.COST);
            if (Math.abs(cost) < MIN_COST) {
                continue;
            }
            String meter = row.dimension("Meter");
            String subCategory = row.dimension("MeterSubCategory");
            String category = blankTo(row.dimension("MeterCategory"), "Other");
            charges.add(new ServiceCharge(
                    category,
                    // "Global" for the charges that have no location — purchases, support, tax —
                    // matching the word the AWS section already uses for them.
                    blankTo(row.dimension("ResourceLocation"), "Global"),
                    // The meter name is what a reader recognises; falling back through the
                    // subcategory to the category keeps the column populated on a degraded
                    // grouping instead of showing an em dash on every row.
                    blankTo(meter, blankTo(subCategory, category)),
                    blankTo(subCategory, meter),
                    row.dimension("UnitOfMeasure"),
                    row.measure(AzureCostQuery.QUANTITY),
                    cost));
        }
        charges.sort(Comparator.comparingDouble((ServiceCharge c) -> -Math.abs(c.cost())));
        return charges;
    }

    // ── Charges by resource ─────────────────────────────────────────────────────

    /**
     * What each individual resource cost — the VM, the disk, the storage account.
     *
     * <p>Has no equivalent anywhere else in the product: the breakdown says "Storage — ₹8,410" and
     * this says which account. Azure's resource id is a full ARM path, which carries the resource
     * group and the ARM type as well as the name, so those come out of the id itself rather than
     * costing extra dimensions the scope might refuse.
     *
     * <p>Null when the scope will not group by resource id at all. Reported as unavailable rather
     * than degraded, because this is the one section whose shorter groupings answer a different
     * question — meter categories under a heading promising resources would be worse than nothing.
     *
     * <p>The total and count are computed over every resource and before the cap, so the caller can
     * honestly say "top 500 of 3,214" rather than summing the page it was handed.
     */
    public TopResources getTopResources(String subscriptionId, YearMonth month,
                                        boolean forceRefresh) {
        return cached(key("top-resources", subscriptionId, month),
                new TypeReference<TopResources>() {}, forceRefresh,
                () -> computeTopResources(subscriptionId, month),
                top -> top != null && !top.resources().isEmpty());
    }

    private TopResources computeTopResources(String subscriptionId, YearMonth month) {
        Result result = run(subscriptionId, new Request(
                month.atDay(1), endOf(month), "ActualCost",
                List.of(AzureCostQuery.COST),
                List.of(
                        List.of(dim("ResourceId"), dim("MeterCategory"), dim("ResourceLocation")),
                        List.of(dim("ResourceId"), dim("MeterCategory")),
                        List.of(dim("ResourceId"), dim("ResourceLocation")),
                        List.of(dim("ResourceId")))));

        if (!result.has("ResourceId")) {
            log.info("[Azure cost] Per-resource cost unavailable for subscription {} — the scope"
                    + " would not group by ResourceId", subscriptionId);
            return null;
        }

        // Folded again in Java: one resource appears once per meter category, and the section shows
        // a resource's total. Costs are summed, and the first non-blank service and region win —
        // a VM's compute and its bandwidth are the same resource in the same place.
        Map<String, double[]> costs = new LinkedHashMap<>();
        Map<String, String[]> labels = new LinkedHashMap<>();
        for (Row row : result.rows()) {
            String resourceId = row.dimension("ResourceId");
            costs.computeIfAbsent(resourceId, k -> new double[1])[0] += row.measure(AzureCostQuery.COST);
            String[] label = labels.computeIfAbsent(resourceId, k -> new String[2]);
            if (label[0] == null || label[0].isBlank()) {
                label[0] = row.dimension("MeterCategory");
            }
            if (label[1] == null || label[1].isBlank()) {
                label[1] = row.dimension("ResourceLocation");
            }
        }

        List<ResourceCost> all = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : costs.entrySet()) {
            double cost = entry.getValue()[0];
            if (Math.abs(cost) < MIN_COST) {
                continue;
            }
            String resourceId = entry.getKey();
            String[] label = labels.getOrDefault(resourceId, new String[2]);
            AzureResourceId parsed = AzureResourceId.parse(resourceId);
            all.add(new ResourceCost(
                    resourceId,
                    parsed.name(),
                    // The meter category where the scope gave one; otherwise the ARM type, which
                    // the id always carries. Both answer "what kind of thing is this", which is
                    // what the column is read as.
                    blankTo(label[0], blankTo(parsed.type(), "Other")),
                    blankTo(label[1], "Global"),
                    // The AWS component renders this as a badge beside the name and omits it when
                    // blank. The resource group is the Azure fact worth putting there: it is how
                    // an Azure resource is found, owned and deleted.
                    parsed.resourceGroup(),
                    cost));
        }

        double totalCost = all.stream().mapToDouble(ResourceCost::cost).sum();
        long groupCount = all.size();
        all.sort(Comparator.comparingDouble((ResourceCost r) -> -Math.abs(r.cost())));
        List<ResourceCost> page = all.size() > MAX_RESOURCES ? all.subList(0, MAX_RESOURCES) : all;

        log.info("✅ [Azure cost] Top resources — {} of {} rows for subscription {} ({})",
                page.size(), groupCount, subscriptionId, month);
        return new TopResources(List.copyOf(page), totalCost, groupCount);
    }

    // ── Network and data transfer ───────────────────────────────────────────────

    /**
     * Network spend, split into the categories that drive it, with the route each charge took.
     *
     * <p>Routinely the largest cost nobody can see, for the same reason as on the other two clouds: a
     * service breakdown files NAT gateway processing under "NAT Gateway", cross-zone traffic under
     * "Bandwidth" and geo-replication traffic under "Storage", so the network total never appears
     * anywhere as one number.
     *
     * <p>Categorisation lives in {@link AzureNetworkMeter} rather than here, so it is testable
     * without an Azure subscription — which matters because the meter naming is the uncertain part.
     *
     * <p>{@code routeDataAvailable} is always false: Cost Management has no from/to column at any
     * scope, so every route shown is inferred from the meter and the billed region. The payload says
     * so rather than letting the UI read an inferred route as a reported one.
     */
    public DataTransferBreakdown getNetworkCosts(String subscriptionId, YearMonth month,
                                                 boolean forceRefresh) {
        return cached(key("network", subscriptionId, month),
                new TypeReference<DataTransferBreakdown>() {}, forceRefresh,
                () -> computeNetworkCosts(subscriptionId, month),
                b -> b != null && !b.charges().isEmpty());
    }

    private DataTransferBreakdown computeNetworkCosts(String subscriptionId, YearMonth month) {
        List<TransferCharge> charges = new ArrayList<>();
        for (Row row : invoiceGrain(subscriptionId, month).rows()) {
            String category = row.dimension("MeterCategory");
            String subCategory = row.dimension("MeterSubCategory");
            String meter = row.dimension("Meter");
            if (!AzureNetworkMeter.isNetwork(category, subCategory, meter)) {
                continue;
            }
            double cost = row.measure(AzureCostQuery.COST);
            double quantity = row.measure(AzureCostQuery.QUANTITY);
            // Kept even at zero cost when something moved: a free tier or a credit can zero the
            // charge while the gigabytes are still worth seeing, and the AWS section lists these
            // rows for exactly that reason.
            if (Math.abs(cost) < MIN_COST && quantity <= 0) {
                continue;
            }
            String region = blankTo(row.dimension("ResourceLocation"), "Global");
            AzureNetworkMeter.Route route = AzureNetworkMeter.routeOf(category, subCategory, meter,
                    "Global".equals(region) ? "" : region);
            charges.add(new TransferCharge(
                    AzureNetworkMeter.categoryOf(category, subCategory, meter),
                    blankTo(category, "Other"),
                    // The meter is Azure's nearest equivalent of a usage type: the specific thing
                    // billed, which is what a reader quotes to support.
                    blankTo(meter, blankTo(subCategory, category)),
                    row.dimension("UnitOfMeasure"),
                    quantity,
                    cost,
                    region,
                    route.from(),
                    route.to(),
                    // True whenever a route was produced at all, because none of them came from
                    // Azure — see the method comment.
                    route.isKnown()));
        }
        charges.sort(Comparator.comparingDouble((TransferCharge c) -> -Math.abs(c.cost())));

        List<TransferFlow> flows = networkFlows(subscriptionId, month, charges);

        log.info("✅ [Azure cost] Network costs — {} charges, {} flows for subscription {} ({})",
                charges.size(), flows.size(), subscriptionId, month);
        return new DataTransferBreakdown(List.copyOf(charges), flows, false, !flows.isEmpty());
    }

    /**
     * Which resources moved the traffic, where the scope will say.
     *
     * <p>A separate query from the charges, and deliberately not derived from them: grouping by
     * resource id is the attempt most likely to be refused, and losing the whole section because the
     * finest grain was unavailable would be the wrong trade. An empty list here leaves the category
     * and route views intact.
     *
     * <p>Azure names only the resource being charged, never the far end — the same limit a CUR has —
     * so a flow's destination is a location or the internet and never a peer id. The section says so
     * rather than leaving a blank that reads as a bug.
     */
    private List<TransferFlow> networkFlows(String subscriptionId, YearMonth month,
                                            List<TransferCharge> charges) {
        Result result;
        try {
            result = run(subscriptionId, new Request(
                    month.atDay(1), endOf(month), "ActualCost",
                    List.of(AzureCostQuery.COST, AzureCostQuery.QUANTITY),
                    List.of(
                            List.of(dim("ResourceId"), dim("MeterCategory"), dim("Meter")),
                            List.of(dim("ResourceId"), dim("Meter")),
                            List.of(dim("ResourceId"), dim("MeterCategory")))));
        } catch (Exception e) {
            // The charges are already in hand; a failure here costs the finest view and nothing else.
            log.debug("[Azure cost] Per-resource network flows unavailable for {}: {}",
                    subscriptionId, e.getMessage());
            return List.of();
        }
        if (!result.has("ResourceId")) {
            return List.of();
        }

        // Category totals come from the charges, which are complete, so a flow can say what share of
        // its category it represents even though only the costliest few are listed.
        Map<String, Double> categoryCost = new LinkedHashMap<>();
        for (TransferCharge charge : charges) {
            categoryCost.merge(charge.category(), charge.cost(), Double::sum);
        }

        List<TransferFlow> flows = new ArrayList<>();
        Map<String, Long> categoryFlows = new LinkedHashMap<>();
        for (Row row : result.rows()) {
            String resourceId = row.dimension("ResourceId");
            String category = row.dimension("MeterCategory");
            String meter = row.dimension("Meter");
            if (resourceId.isBlank() || !AzureNetworkMeter.isNetwork(category, "", meter)) {
                continue;
            }
            double cost = row.measure(AzureCostQuery.COST);
            double quantity = row.measure(AzureCostQuery.QUANTITY);
            if (Math.abs(cost) < MIN_COST && quantity <= 0) {
                continue;
            }
            String bucket = AzureNetworkMeter.categoryOf(category, "", meter);
            categoryFlows.merge(bucket, 1L, Long::sum);

            AzureResourceId parsed = AzureResourceId.parse(resourceId);
            AzureNetworkMeter.Route route = AzureNetworkMeter.routeOf(category, "", meter,
                    parsed.region());
            boolean gbDenominated = isGigabyteMeter(meter);
            flows.add(new TransferFlow(
                    bucket,
                    resourceId,
                    parsed.name(),
                    blankTo(category, blankTo(parsed.type(), "Other")),
                    blankTo(parsed.region(), "Global"),
                    // Cost Management carries no availability zone at any scope. Left blank rather
                    // than filled with the region, which would read as a zone that does not exist.
                    "",
                    route.from(),
                    route.to(),
                    route.isKnown(),
                    blankTo(meter, category),
                    // GB and hours kept apart, never summed: a gateway bills both and their total
                    // is a quantity of nothing.
                    gbDenominated ? quantity : 0.0,
                    gbDenominated ? 0.0 : quantity,
                    cost,
                    0.0,
                    0L));
        }

        flows.sort(Comparator.comparingDouble((TransferFlow f) -> -Math.abs(f.cost())));
        // Category totals are stamped on afterwards so every flow in a category agrees about it.
        List<TransferFlow> withTotals = new ArrayList<>(flows.size());
        for (TransferFlow f : flows) {
            withTotals.add(new TransferFlow(f.category(), f.resourceId(), f.name(), f.service(),
                    f.region(), f.availabilityZone(), f.fromLocation(), f.toLocation(),
                    f.routeDerived(), f.usageType(), f.gigabytes(), f.hours(), f.cost(),
                    categoryCost.getOrDefault(f.category(), f.cost()),
                    categoryFlows.getOrDefault(f.category(), 1L)));
        }
        return List.copyOf(withTotals);
    }

    /**
     * Whether a meter's usage is denominated in gigabytes rather than hours.
     *
     * <p>{@code UnitOfMeasure} would answer this outright, but it costs a grouping the resource query
     * can ill afford — so it is read off the meter name, where Azure is consistent: transfer and
     * processing meters are per-GB and gateway, tunnel and rule meters are per-hour.
     */
    private static boolean isGigabyteMeter(String meter) {
        String name = meter == null ? "" : meter.toLowerCase(Locale.ROOT);
        if (name.contains("hour") || name.contains("gateway") && !name.contains("data")) {
            return false;
        }
        return name.contains("data") || name.contains("transfer") || name.contains("egress")
                || name.contains("ingress") || name.contains("processed") || name.contains("gb");
    }

    // ── One resource, in depth ──────────────────────────────────────────────────

    /**
     * Everything the resource drill-down needs for one resource.
     *
     * <p>Two queries: the meters for both months, and the current month by day. Both grouped by
     * {@code ResourceId} so Cost Management filters server-side rather than us pulling the subscription
     * and discarding all but one resource — which on a large subscription would be the difference
     * between a handful of rows and the page ceiling.
     *
     * <p>Null when the scope will not group by resource, which is the same condition that makes the
     * resource table itself unavailable. A reader who can see the table can open the panel; one who
     * cannot never reaches it.
     */
    public ResourceChange getResourceChange(String subscriptionId, String resourceId, YearMonth month,
                                            boolean forceRefresh) {
        String cacheKey = "azure:cost-insight:resource-change:" + subscriptionId + ":"
                + resourceId.toLowerCase(Locale.ROOT) + ":" + month;
        return cached(cacheKey, new TypeReference<ResourceChange>() {}, forceRefresh,
                () -> computeResourceChange(subscriptionId, resourceId, month),
                change -> change != null);
    }

    private ResourceChange computeResourceChange(String subscriptionId, String resourceId,
                                                 YearMonth month) {
        YearMonth prior = month.minusMonths(1);
        Result current = metersForResource(subscriptionId, resourceId, month);
        if (!current.has("ResourceId") && current.rows().isEmpty()) {
            log.info("[Azure cost] Resource drill-down unavailable for {} — the scope would not group"
                    + " by ResourceId", subscriptionId);
            return null;
        }
        Result before = metersForResource(subscriptionId, resourceId, prior);

        Map<String, double[]> currentByMeter = foldByMeter(current, resourceId);
        Map<String, double[]> priorByMeter = foldByMeter(before, resourceId);

        java.util.LinkedHashSet<String> meters = new java.util.LinkedHashSet<>(currentByMeter.keySet());
        meters.addAll(priorByMeter.keySet());

        List<ResourceLineChange> lines = new ArrayList<>();
        double priorCost = 0;
        double currentCost = 0;
        double priorQty = 0;
        double currentQty = 0;

        for (String meter : meters) {
            double[] cur = currentByMeter.getOrDefault(meter, new double[]{0, 0});
            double[] pri = priorByMeter.getOrDefault(meter, new double[]{0, 0});
            priorCost += pri[0];
            currentCost += cur[0];
            priorQty += pri[1];
            currentQty += cur[1];

            // Per meter the split is exact rather than indicative: one meter bills in one unit, so cost
            // ÷ quantity is a real unit rate. The service-level split has to blend units and can only
            // point at a direction — this is what gives the panel its depth over the table.
            PriceVolumeSplit.Effects effects = PriceVolumeSplit.of(pri[0], cur[0], pri[1], cur[1]);
            lines.add(new ResourceLineChange(meter, meter, "",
                    pri[0], cur[0], cur[0] - pri[0],
                    pri[1], cur[1],
                    effects.priceEffect(), effects.volumeEffect()));
        }
        lines.sort(Comparator.comparingDouble((ResourceLineChange l) -> -Math.abs(l.costChange())));

        PriceVolumeSplit.Effects total =
                PriceVolumeSplit.of(priorCost, currentCost, priorQty, currentQty);
        AzureResourceId parsed = AzureResourceId.parse(resourceId);
        // Service and region come off whichever month has rows, so a resource deleted mid-comparison —
        // exactly the one a reader most wants to open — still names itself.
        String service = firstDimension(current, "MeterCategory", firstDimension(before, "MeterCategory",
                blankTo(parsed.type(), "Other")));

        return new ResourceChange(resourceId, parsed.name(), service, "Global",
                parsed.resourceGroup(),
                priorCost, currentCost, currentCost - priorCost,
                priorQty, currentQty,
                total.priceEffect(), total.volumeEffect(),
                true,
                List.copyOf(lines),
                dailyForResource(subscriptionId, resourceId, month));
    }

    /**
     * One resource's meters for one month.
     *
     * <p>Grouped by {@code ResourceId} as well as the meter so the rows can be filtered to this resource
     * — Cost Management has no resource filter in the shape this query uses, so the grouping is what
     * scopes it. The ids come back lowercased, which is why the comparison below is case-insensitive.
     */
    private Result metersForResource(String subscriptionId, String resourceId, YearMonth month) {
        try {
            return run(subscriptionId, new Request(
                    month.atDay(1), endOf(month), "ActualCost",
                    List.of(AzureCostQuery.COST, AzureCostQuery.QUANTITY),
                    List.of(
                            List.of(dim("ResourceId"), dim("Meter")),
                            List.of(dim("ResourceId"), dim("MeterCategory")),
                            List.of(dim("ResourceId")))));
        } catch (Exception e) {
            log.debug("[Azure cost] Resource meters unavailable for {} / {}: {}", subscriptionId,
                    resourceId, e.getMessage());
            return Result.EMPTY;
        }
    }

    /** Sums a result down to meter → [cost, quantity], keeping only this resource's rows. */
    private Map<String, double[]> foldByMeter(Result result, String resourceId) {
        Map<String, double[]> byMeter = new LinkedHashMap<>();
        for (Row row : result.rows()) {
            if (!resourceId.equalsIgnoreCase(row.dimension("ResourceId"))) {
                continue;
            }
            String meter = blankTo(row.dimension("Meter"),
                    blankTo(row.dimension("MeterCategory"), "Other"));
            double[] slot = byMeter.computeIfAbsent(meter, k -> new double[2]);
            slot[0] += row.measure(AzureCostQuery.COST);
            slot[1] += row.measure(AzureCostQuery.QUANTITY);
        }
        return byMeter;
    }

    /** The first non-blank value of a dimension across a result, or the fallback. */
    private String firstDimension(Result result, String dimension, String fallback) {
        for (Row row : result.rows()) {
            String value = row.dimension(dimension);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    /**
     * One resource's cost per day over the month.
     *
     * <p>The one query here that asks for a granularity rather than letting the ladder drop it — a daily
     * series is precisely what it needs, so {@code UsageDate} has to come back as a column.
     */
    private List<DailyCost> dailyForResource(String subscriptionId, String resourceId, YearMonth month) {
        Result result;
        try {
            result = run(subscriptionId, new Request(
                    month.atDay(1), endOf(month), "ActualCost",
                    List.of(AzureCostQuery.COST),
                    List.of(List.of(dim("ResourceId"), dim("UsageDate")))));
        } catch (Exception e) {
            // The panel's other two tabs are already in hand; a missing trend costs one chart.
            log.debug("[Azure cost] Daily series unavailable for {} / {}: {}", subscriptionId,
                    resourceId, e.getMessage());
            return List.of();
        }

        Map<String, Double> byDay = new java.util.TreeMap<>();
        for (Row row : result.rows()) {
            if (!resourceId.equalsIgnoreCase(row.dimension("ResourceId"))) {
                continue;
            }
            String date = isoDate(row.dimension("UsageDate"));
            if (date != null) {
                byDay.merge(date, row.measure(AzureCostQuery.COST), Double::sum);
            }
        }
        List<DailyCost> daily = new ArrayList<>(byDay.size());
        byDay.forEach((date, cost) -> daily.add(new DailyCost(date, cost)));
        return List.copyOf(daily);
    }

    /**
     * Cost Management's usage date as an ISO string.
     *
     * <p>It arrives as a numeric {@code YYYYMMDD} — 20260815 — which is neither a date nor sortable as
     * a label. Null for anything unparseable, so a bad row is dropped rather than plotted at an
     * arbitrary position on the axis.
     */
    private static String isoDate(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.trim();
        int dot = digits.indexOf('.');
        if (dot > 0) {
            digits = digits.substring(0, dot);
        }
        try {
            if (digits.length() == 8) {
                return LocalDate.parse(digits, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                        .toString();
            }
            if (digits.length() >= 10) {
                return LocalDate.parse(digits.substring(0, 10)).toString();
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    // ── Price vs volume ─────────────────────────────────────────────────────────

    /**
     * Why spend changed: how much of each service's movement was rate, and how much was usage.
     *
     * <p>Needs usage quantity beside cost, which is the one aggregation a scope may refuse. When it
     * does, this returns null and the caller reports the section as unavailable — a split computed
     * without quantities would attribute everything to price, which is a statement about the query
     * rather than the bill.
     *
     * <p>The same caveat as on the other two clouds: quantity is summed across a service's meters,
     * which mix units — gibibytes with hours with operations — so the split is a strong indicator
     * rather than an exact attribution. A service billed in one unit reads exactly; a broad one reads
     * directionally.
     */
    public List<PriceVolumeChange> getPriceVsVolume(String subscriptionId, YearMonth current,
                                                    boolean forceRefresh) {
        return cached(key("price-vs-volume", subscriptionId, current),
                new TypeReference<List<PriceVolumeChange>>() {}, forceRefresh,
                () -> computePriceVsVolume(subscriptionId, current),
                rows -> rows != null && !rows.isEmpty());
    }

    private List<PriceVolumeChange> computePriceVsVolume(String subscriptionId, YearMonth current) {
        YearMonth prior = current.minusMonths(1);
        Result now = byService(subscriptionId, current);
        if (!now.hasMetric(AzureCostQuery.QUANTITY)) {
            log.info("[Azure cost] Price vs volume unavailable for subscription {} — the scope"
                    + " would not aggregate UsageQuantity", subscriptionId);
            return null;
        }
        Result before = byService(subscriptionId, prior);

        // [priorCost, currentCost, priorQty, currentQty]
        Map<String, double[]> byService = new LinkedHashMap<>();
        for (Row row : before.rows()) {
            double[] slot = byService.computeIfAbsent(
                    blankTo(row.dimension("MeterCategory"), "Other"), k -> new double[4]);
            slot[0] += row.measure(AzureCostQuery.COST);
            slot[2] += row.measure(AzureCostQuery.QUANTITY);
        }
        for (Row row : now.rows()) {
            double[] slot = byService.computeIfAbsent(
                    blankTo(row.dimension("MeterCategory"), "Other"), k -> new double[4]);
            slot[1] += row.measure(AzureCostQuery.COST);
            slot[3] += row.measure(AzureCostQuery.QUANTITY);
        }

        List<PriceVolumeChange> changes = new ArrayList<>();
        byService.forEach((service, s) -> {
            double priorCost = s[0];
            double currentCost = s[1];
            double priorQty = s[2];
            double currentQty = s[3];
            // Shared with the AWS and GCP paths and with the per-resource breakdown, so all four report
            // the same split — including the new-service case this copy used to get backwards. See
            // PriceVolumeSplit for the decomposition and why price is taken as the residual.
            PriceVolumeSplit.Effects effects =
                    PriceVolumeSplit.of(priorCost, currentCost, priorQty, currentQty);
            changes.add(new PriceVolumeChange(service, priorCost, currentCost,
                    currentCost - priorCost, priorQty, currentQty,
                    effects.priceEffect(), effects.volumeEffect()));
        });
        changes.sort(Comparator.comparingDouble((PriceVolumeChange c) -> -Math.abs(c.costChange())));
        return changes.size() > MAX_PRICE_VOLUME_SERVICES
                ? List.copyOf(changes.subList(0, MAX_PRICE_VOLUME_SERVICES))
                : List.copyOf(changes);
    }

    private Result byService(String subscriptionId, YearMonth month) {
        return run(subscriptionId, new Request(
                month.atDay(1), endOf(month), "ActualCost",
                List.of(AzureCostQuery.COST, AzureCostQuery.QUANTITY),
                List.of(List.of(dim("MeterCategory")))));
    }

    // ── Charge types ────────────────────────────────────────────────────────────

    /**
     * Usage against purchases, refunds, unused commitment and rounding.
     *
     * <p>Queried as amortized rather than actual cost, which is what separates a reservation purchase
     * from the part of it that went unused: on an actual-cost view a year's reservation is one
     * {@code Purchase} in the month it was bought and nothing afterwards, so the months it covers look
     * free and the month it was bought looks like a disaster.
     */
    public List<ChargeTypeAmount> getChargeTypeBreakdown(String subscriptionId, YearMonth month,
                                                         boolean forceRefresh) {
        return cached(key("charge-types", subscriptionId, month),
                new TypeReference<List<ChargeTypeAmount>>() {}, forceRefresh,
                () -> computeChargeTypes(subscriptionId, month),
                rows -> !rows.isEmpty());
    }

    private List<ChargeTypeAmount> computeChargeTypes(String subscriptionId, YearMonth month) {
        Result result = run(subscriptionId, new Request(
                month.atDay(1), endOf(month), "AmortizedCost",
                List.of(AzureCostQuery.COST),
                List.of(
                        List.of(dim("ChargeType")),
                        List.of(dim("PublisherType")),
                        List.of(dim("PricingModel")))));

        List<ChargeTypeAmount> amounts = new ArrayList<>();
        for (Row row : result.rows()) {
            double cost = row.measure(AzureCostQuery.COST);
            if (Math.abs(cost) < MIN_COST) {
                continue;
            }
            String label = result.groupedBy().isEmpty() ? ""
                    : row.dimension(result.groupedBy().get(0).columnName());
            amounts.add(new ChargeTypeAmount(blankTo(readable(label), "Usage"), cost));
        }
        amounts.sort(Comparator.comparingDouble((ChargeTypeAmount a) -> -Math.abs(a.cost())));
        return amounts;
    }

    // ── Unused commitment ───────────────────────────────────────────────────────

    /**
     * Reservation and savings plan spend that bought nothing.
     *
     * <p>Azure reports this directly, as {@code UnusedReservation} and {@code UnusedSavingsPlan}
     * charge types on an amortized view, so no arithmetic is needed — unlike AWS, where it has to be
     * assembled from the report's fee columns. Those two columns are what
     * {@code unusedRecurringFee} and {@code unusedUpfrontFee} carry here; the AWS field names are
     * kept so the payload shape matches, and the total is the number the UI actually shows.
     */
    public UnusedCommitment getUnusedCommitment(String subscriptionId, YearMonth month,
                                                boolean forceRefresh) {
        return cached(key("unused-commitment", subscriptionId, month),
                new TypeReference<UnusedCommitment>() {}, forceRefresh,
                () -> computeUnusedCommitment(subscriptionId, month),
                u -> u != null && u.total() != 0.0);
    }

    private UnusedCommitment computeUnusedCommitment(String subscriptionId, YearMonth month) {
        Result result = run(subscriptionId, new Request(
                month.atDay(1), endOf(month), "AmortizedCost",
                List.of(AzureCostQuery.COST),
                List.of(List.of(dim("ChargeType")))));
        if (!result.has("ChargeType")) {
            return null;
        }

        double reservation = 0;
        double savingsPlan = 0;
        for (Row row : result.rows()) {
            String type = row.dimension("ChargeType").toLowerCase(Locale.ROOT);
            if (type.contains("unusedreservation") || type.contains("unused reservation")) {
                reservation += row.measure(AzureCostQuery.COST);
            } else if (type.contains("unusedsavingsplan") || type.contains("unused savings")) {
                savingsPlan += row.measure(AzureCostQuery.COST);
            }
        }
        return new UnusedCommitment(reservation, savingsPlan, reservation + savingsPlan);
    }

    // ── Breakdown dimensions and tags ───────────────────────────────────────────

    /** The dimensions the cost breakdown can be grouped by, and the Azure column behind each. */
    public enum BreakdownDimension {
        SERVICE("MeterCategory"),
        REGION("ResourceLocation"),
        // Both spellings, most likely first. Microsoft's Bicep samples use ResourceGroupName and its
        // REST answers use ResourceGroup, and a rejected name is a 400 rather than a missing column.
        RESOURCE_GROUP("ResourceGroupName", "ResourceGroup"),
        RESOURCE_TYPE("ResourceType"),
        CHARGE_TYPE("ChargeType"),
        PRICING_MODEL("PricingModel"),
        PUBLISHER("PublisherType");

        private final List<String> requestNames;

        BreakdownDimension(String... requestNames) {
            this.requestNames = List.of(requestNames);
        }

        /** The canonical column callers read the value back under. */
        public String column() {
            return requestNames.get(0);
        }

        /** Dimension names to ask Azure for, in order, until one is accepted. */
        public List<String> requestNames() {
            return requestNames;
        }
    }

    /**
     * Spend for a date range, grouped by one dimension — what the breakdown donut and table read.
     *
     * <p>Takes explicit dates rather than a month because this is the one section the Cost page lets
     * a reader filter, and "1st to the 12th" is a question the existing Azure endpoints cannot be
     * asked: they serve a fixed month-to-date figure out of the ingestion cache.
     *
     * <p>{@code ResourceGroupName} is the addition that matters most for Azure. A resource group is
     * how Azure spend is actually owned — one team, one application, one environment — so grouping by
     * it answers chargeback questions that a meter-category breakdown cannot, and it has no AWS
     * equivalent to have been copied from.
     */
    public List<CostAmount> getCostByDimension(String subscriptionId, LocalDate start, LocalDate end,
                                               BreakdownDimension dimension, boolean forceRefresh) {
        String cacheKey = "azure:cost-insight:breakdown:" + subscriptionId + ":" + dimension
                + ":" + start + ":" + end;
        return cached(cacheKey, new TypeReference<List<CostAmount>>() {}, forceRefresh,
                () -> toAmounts(run(subscriptionId, new Request(
                        start, end, "ActualCost",
                        List.of(AzureCostQuery.COST),
                        // Each alternative spelling is its own rung, because the request name is
                        // validated by Azure before any column is returned — an alias on the response
                        // side cannot rescue a dimension name the query was rejected for. Microsoft's
                        // own templates use both ResourceGroupName and ResourceGroup.
                        dimension.requestNames().stream()
                                .map(name -> List.of(dim(name)))
                                .toList())), dimension.column()),
                rows -> !rows.isEmpty());
    }

    /**
     * Spend for a date range, grouped by the values of one tag key — the chargeback view.
     *
     * <p>Azure's tag grouping returns a {@code TagValue} column and folds untagged spend into a blank
     * value, which is reported as "Untagged" rather than dropped: unattributed spend is the finding,
     * not the gap in it.
     */
    public List<CostAmount> getCostByTag(String subscriptionId, LocalDate start, LocalDate end,
                                         String tagKey, boolean forceRefresh) {
        String cacheKey = "azure:cost-insight:by-tag:" + subscriptionId + ":" + tagKey
                + ":" + start + ":" + end;
        return cached(cacheKey, new TypeReference<List<CostAmount>>() {}, forceRefresh,
                () -> toAmounts(run(subscriptionId, new Request(
                        start, end, "ActualCost",
                        List.of(AzureCostQuery.COST),
                        List.of(List.of(Grouping.tagKey(tagKey))))), "TagValue"),
                rows -> !rows.isEmpty());
    }

    private List<CostAmount> toAmounts(Result result, String column) {
        List<CostAmount> amounts = new ArrayList<>();
        for (Row row : result.rows()) {
            double cost = row.measure(AzureCostQuery.COST);
            if (Math.abs(cost) < MIN_COST) {
                continue;
            }
            String name = row.dimension(column);
            amounts.add(new CostAmount(
                    blankTo(name, "TagValue".equals(column) ? "Untagged" : "(Unassigned)"), cost));
        }
        amounts.sort(Comparator.comparingDouble((CostAmount a) -> -a.amount()));
        return amounts;
    }

    /**
     * Every tag key Cost Management knows about for a subscription, for the chargeback picker.
     *
     * <p>A different endpoint from the query — tag keys are metadata, and there is no grouping that
     * lists them. An empty list is a valid answer, so a failure here returns empty rather than
     * throwing: a missing picker is a smaller loss than a section that will not render.
     */
    public List<String> getTagKeys(String subscriptionId, boolean forceRefresh) {
        return cached("azure:cost-insight:tag-keys:" + subscriptionId,
                new TypeReference<List<String>>() {}, forceRefresh,
                () -> fetchTagKeys(subscriptionId),
                keys -> !keys.isEmpty());
    }

    private List<String> fetchTagKeys(String subscriptionId) {
        String url = AzureArmClient.ARM_BASE + "/subscriptions/" + subscriptionId
                + "/providers/Microsoft.CostManagement/dimensions?api-version=2023-03-01"
                + "&$filter=properties/category%20eq%20%27TagKey%27";
        try {
            Optional<String> response = armClient.getQuietly(url, armClient.getToken(subscriptionId));
            if (response.isEmpty()) {
                return List.of();
            }
            List<String> keys = new ArrayList<>();
            objectMapper.readTree(response.get()).path("value")
                    .forEach(entry -> entry.path("properties").path("data")
                            .forEach(value -> {
                                String key = value.asText("");
                                if (!key.isBlank()) {
                                    keys.add(key);
                                }
                            }));
            keys.sort(String.CASE_INSENSITIVE_ORDER);
            return List.copyOf(keys);
        } catch (Exception e) {
            log.debug("[Azure cost] Could not list tag keys for {}: {}", subscriptionId,
                    e.getMessage());
            return List.of();
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────

    private Result run(String subscriptionId, Request request) {
        return query.run(subscriptionId, armClient.getToken(subscriptionId), request);
    }

    private static Grouping dim(String name) {
        return Grouping.dimension(name);
    }

    /**
     * The full month, including the current one — deliberately not capped at today.
     *
     * <p>The same trap as on the AWS side, and it bites harder here. Not every Azure charge is dated
     * on the day it is incurred: reservation and savings plan amortisation, support and Marketplace
     * subscriptions carry dates spanning the whole billing period from the day it opens, and they are
     * already on the invoice. Capping at today drops them, which makes this service disagree with
     * {@code AzureBillingDataIngestionService} — whose month-to-date figure is what the KPI cards
     * show — for no reason a reader could ever work out.
     */
    private static LocalDate endOf(YearMonth month) {
        return month.atEndOfMonth();
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
        // A null result means "this scope cannot answer", which is a stable fact about the
        // subscription rather than a transient one — but it is cached briefly all the same, because
        // the thing that makes it null is sometimes a throttled query rather than a real limit.
        redisCacheService.put(key, fresh,
                fresh != null && hasRows.test(fresh) ? SECTION_TTL_MINUTES : EMPTY_TTL_MINUTES);
        return fresh;
    }

    private static String key(String section, String subscriptionId, YearMonth month) {
        return "azure:cost-insight:" + section + ":" + subscriptionId + ":" + month;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** "UnusedReservation" → "Unused reservation", so a charge type reads as a label. */
    private static String readable(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String spaced = raw.replaceAll("(?<=[a-z])(?=[A-Z])", " ").replace('_', ' ').trim();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1).toLowerCase(Locale.ROOT);
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
     * @param availabilityZone always empty on Azure — Cost Management carries no zone at any scope
     * @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.TransferFlow
     */
    public record TransferFlow(String category, String resourceId, String name,
                               String service, String region, String availabilityZone,
                               String fromLocation, String toLocation, boolean routeDerived,
                               String usageType,
                               double gigabytes, double hours, double cost,
                               double categoryCost, long categoryFlows) {
    }

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.ChargeTypeAmount */
    public record ChargeTypeAmount(String chargeType, double cost) {
    }

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.UnusedCommitment */
    public record UnusedCommitment(double unusedRecurringFee, double unusedUpfrontFee,
                                   double total) {
    }

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.ServiceCharge */
    public record ServiceCharge(String service, String region, String description,
                                String usageType, String unit, double quantity, double cost) {
    }

    /**
     * @param instanceType the resource group on Azure. The AWS component renders this as a badge
     *                     beside the name, and the resource group is the Azure fact worth putting
     *                     there — it is how a resource is found, owned and deleted.
     * @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.ResourceCost
     */
    public record ResourceCost(String resourceId, String name, String service, String region,
                               String instanceType, double cost) {
    }

    /** @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.TopResources */
    public record TopResources(List<ResourceCost> resources, double totalCost, long groupCount) {
    }

    /**
     * @param region always "Global" on Azure — the resource drill-down does not spend a grouping on
     *               location, which the scope may refuse, when the table the reader came from already
     *               showed it
     * @see com.xammer.cloud.service.cur.CurAdvancedMetricsService.ResourceChange
     */
    public record ResourceChange(String resourceId, String name, String service, String region,
                                 String instanceType,
                                 double priorCost, double currentCost, double costChange,
                                 double priorQuantity, double currentQuantity,
                                 double priceEffect, double volumeEffect,
                                 /**
                                  * Always true on Azure: Cost Management serves roughly the last 13
                                  * months for any subscription, so unlike a CUR there is no "the report
                                  * does not reach back that far" case to report. Present so the payload
                                  * matches the other two clouds and one panel renders all three.
                                  */
                                 boolean comparisonAvailable,
                                 List<ResourceLineChange> lines,
                                 List<DailyCost> daily) {
    }

    /**
     * @param unit always empty on Azure — {@code UnitOfMeasure} would cost a third grouping on the query
     *             most likely to be refused, and the quantity is shown beside its meter name regardless
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

    /**
     * One row of the cost breakdown.
     *
     * <p>Field names match {@code CostDto} — {@code name} and {@code amount} — because the breakdown
     * table on every cost page reads those two, and a record is enough: the trend and estimate fields
     * on {@code CostDto} are computed by the AWS path and would be empty here.
     */
    public record CostAmount(String name, double amount) {
    }
}
