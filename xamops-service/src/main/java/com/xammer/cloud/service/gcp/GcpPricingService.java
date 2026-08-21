package com.xammer.cloud.service.gcp;

import com.google.cloud.billing.v1.CloudCatalogClient;
import com.google.cloud.billing.v1.ListSkusRequest;
import com.google.cloud.billing.v1.PricingExpression;
import com.google.cloud.billing.v1.Sku;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compute Engine list prices, read from the Cloud Billing Catalog API.
 *
 * <p>All prices returned by this service are <strong>USD per hour</strong>, matching every other
 * pricing service in the codebase. It previously returned INR, converted with a hardcoded
 * {@code USD_TO_INR_RATE = 83.0}, while {@code GcpOptimizationService} returned USD. The GCP
 * dashboard added the two together, so its headline potential-savings figure counted the
 * autospotting component about 83 times over.
 *
 * <p>It also previously called no API at all. {@code fetchPricingFromAPI} computed prices from a
 * switch statement of per-family constants and then multiplied by one of four regional
 * multipliers covering 40-odd regions. The billing client, the Compute Engine service id and the
 * injected client provider were all present but unused.
 *
 * <h2>How the catalog is read</h2>
 * A Compute Engine VM is billed as two meters: a per-vCPU-hour core charge and a per-GiB-hour RAM
 * charge, each published per region and per usage type. So the price of a machine type is
 * {@code vCPUs x coreRate + memoryGiB x ramRate}, and there is no regional multiplier to apply —
 * the catalog rate is already the rate for that region.
 *
 * <p>{@code listSkus} cannot be filtered by region server-side, so one walk of the Compute Engine
 * catalog populates a rate table for every region at once. That walk is done at most once per
 * {@link #CATALOG_TTL_MS} rather than once per machine type.
 *
 * <h2>What is not verified</h2>
 * SKU descriptions are matched by pattern, and those patterns have not been checked against a
 * live catalog response — this deployment has no GCP credentials available to it. Everything is
 * therefore written to fail closed: a family that cannot be resolved yields
 * {@link PriceSource#UNAVAILABLE} rather than a guess, and callers are expected to treat an
 * unpriced result as unknown instead of zero. {@link PriceSource#ESTIMATE_TABLE} is only reached
 * when the catalog is unreachable, and says so.
 */
@Service
@Slf4j
public class GcpPricingService {

    private final GcpClientProvider gcpClientProvider;

    /** Compute Engine, as published by the Cloud Billing Catalog. */
    private static final String COMPUTE_ENGINE_SERVICE = "services/6F81-5844-456A";

    /** Catalog list prices are quoted in USD; asked for explicitly so a billing account's own
     *  currency preference cannot silently change the unit of everything downstream. */
    private static final String CATALOG_CURRENCY = "USD";

    private static final long CATALOG_TTL_MS = 24 * 60 * 60 * 1000L;

    /** Hours in a billing month, matching the rest of the codebase. */
    public static final double HOURS_PER_MONTH = 730.0;

    /** Core charges are quoted per hour. */
    private static final String CORE_USAGE_UNIT = "h";

    /** RAM charges are quoted per gibibyte-hour. */
    private static final String RAM_USAGE_UNIT = "GiBy.h";

    /**
     * Where a price came from. Exposed so a caller can decline to present a figure it should not
     * present, rather than every number looking equally authoritative.
     */
    public enum PriceSource {
        /** Live Cloud Billing Catalog list price for the requested region. */
        CATALOG,
        /** Catalog unreachable; a coarse per-family estimate that ignores region. */
        ESTIMATE_TABLE,
        /** Neither the catalog nor the estimate table could price this machine type. */
        UNAVAILABLE
    }

    /**
     * Resolved catalog rates for one region, one family and one usage type.
     *
     * @param coreUsdPerHour per-vCPU-hour rate
     * @param ramUsdPerGibHour per-GiB-hour rate
     */
    private record FamilyRates(Double coreUsdPerHour, Double ramUsdPerGibHour) {

        FamilyRates withCore(double core) {
            return new FamilyRates(core, ramUsdPerGibHour);
        }

        FamilyRates withRam(double ram) {
            return new FamilyRates(coreUsdPerHour, ram);
        }

        boolean isComplete() {
            return coreUsdPerHour != null && ramUsdPerGibHour != null;
        }
    }

    /**
     * The catalog rate table: region -> family -> usage type -> rates.
     *
     * <p>Rebuilt wholesale rather than mutated, so a reader never sees a half-populated table.
     */
    private record CatalogSnapshot(Map<String, Map<String, Map<Boolean, FamilyRates>>> byRegion, long builtAtMs) {

        boolean isExpired() {
            return System.currentTimeMillis() - builtAtMs > CATALOG_TTL_MS;
        }

        Optional<FamilyRates> lookup(String region, String family, boolean spot) {
            return Optional.ofNullable(byRegion.get(region))
                    .map(families -> families.get(family))
                    .map(byUsage -> byUsage.get(spot))
                    .filter(FamilyRates::isComplete);
        }
    }

    private final java.util.concurrent.atomic.AtomicReference<CatalogSnapshot> catalog =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /** Guards the catalog walk so a burst of callers triggers one refresh, not one each. */
    private final Object catalogRefreshLock = new Object();

    /** Resolved prices, keyed by machine type and region. */
    private final Map<String, PricingInfo> priceCache = new ConcurrentHashMap<>();

    public GcpPricingService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * List price for a machine type in a region, in USD per hour.
     *
     * <p>Without a project id there are no credentials to call the catalog with, so this can only
     * ever return the estimate table. Prefer
     * {@link #getMachineTypePricing(String, String, String)}.
     */
    public PricingInfo getMachineTypePricing(String machineType, String region) {
        return getMachineTypePricing(machineType, region, null);
    }

    /**
     * List price for a machine type in a region, in USD per hour.
     *
     * @param machineType   e.g. {@code n2-standard-4}, {@code e2-medium}, {@code n2-custom-4-16384}
     * @param region        e.g. {@code asia-south1}
     * @param gcpProjectId  project whose credentials are used to read the catalog; the prices
     *                      themselves are public list prices, not project-specific
     */
    public PricingInfo getMachineTypePricing(String machineType, String region, String gcpProjectId) {
        if (machineType == null || machineType.isBlank()) {
            return PricingInfo.unavailable("no machine type given");
        }
        String normalisedRegion = region == null ? "" : region.trim().toLowerCase(Locale.ROOT);
        String normalisedType = machineType.trim().toLowerCase(Locale.ROOT);

        String cacheKey = normalisedType + ":" + normalisedRegion;
        PricingInfo cached = priceCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        MachineTypeSpecs specs = parseMachineType(normalisedType);
        PricingInfo resolved = resolvePricing(normalisedType, normalisedRegion, gcpProjectId, specs);

        // Only a real answer is worth keeping. Caching UNAVAILABLE would make a transient
        // credential or catalog failure stick for the life of the process.
        if (resolved.getSource() != PriceSource.UNAVAILABLE) {
            priceCache.put(cacheKey, resolved);
        }
        return resolved;
    }

    private PricingInfo resolvePricing(String machineType, String region, String gcpProjectId,
            MachineTypeSpecs specs) {

        if (!specs.recognised()) {
            log.warn("[gcp-pricing] Unrecognised machine type '{}' — reporting unpriced rather than "
                    + "guessing its shape", machineType);
            return PricingInfo.unavailable("machine type '" + machineType + "' could not be parsed");
        }

        if (region.isBlank()) {
            return PricingInfo.unavailable("no region given for " + machineType);
        }

        Optional<PricingInfo> fromCatalog = priceFromCatalog(machineType, region, gcpProjectId, specs);
        if (fromCatalog.isPresent()) {
            return fromCatalog.get();
        }

        return estimateFromTable(machineType, specs);
    }

    private Optional<PricingInfo> priceFromCatalog(String machineType, String region,
            String gcpProjectId, MachineTypeSpecs specs) {

        if (gcpProjectId == null || gcpProjectId.isBlank()) {
            log.debug("[gcp-pricing] No project id supplied for {}; cannot read the billing catalog",
                    machineType);
            return Optional.empty();
        }

        CatalogSnapshot snapshot = currentCatalog(gcpProjectId);
        if (snapshot == null) {
            return Optional.empty();
        }

        Optional<FamilyRates> onDemand = snapshot.lookup(region, specs.catalogFamily(), false);
        if (onDemand.isEmpty()) {
            log.debug("[gcp-pricing] No on-demand catalog rates for family {} in {}",
                    specs.catalogFamily(), region);
            return Optional.empty();
        }

        double onDemandPrice = priceOf(specs, onDemand.get());

        // Spot rates are published as their own SKUs. When a family has no spot SKU in a region,
        // spot capacity is not offered there — reporting an on-demand-derived guess would invent
        // a saving for something that cannot be bought.
        Optional<FamilyRates> spot = snapshot.lookup(region, specs.catalogFamily(), true);
        if (spot.isEmpty()) {
            log.debug("[gcp-pricing] No spot catalog rates for family {} in {}; reporting on-demand "
                    + "only", specs.catalogFamily(), region);
            return Optional.of(PricingInfo.onDemandOnly(onDemandPrice, PriceSource.CATALOG,
                    "No Spot SKU published for " + specs.catalogFamily() + " in " + region));
        }

        return Optional.of(PricingInfo.priced(onDemandPrice, priceOf(specs, spot.get()),
                PriceSource.CATALOG, null));
    }

    private static double priceOf(MachineTypeSpecs specs, FamilyRates rates) {
        return specs.vCpus() * rates.coreUsdPerHour() + specs.memoryGb() * rates.ramUsdPerGibHour();
    }

    // ── Catalog walk ──────────────────────────────────────────────────────────

    private CatalogSnapshot currentCatalog(String gcpProjectId) {
        CatalogSnapshot existing = catalog.get();
        if (existing != null && !existing.isExpired()) {
            return existing;
        }

        synchronized (catalogRefreshLock) {
            // Another thread may have refreshed while this one waited.
            existing = catalog.get();
            if (existing != null && !existing.isExpired()) {
                return existing;
            }

            CatalogSnapshot rebuilt = buildCatalogSnapshot(gcpProjectId);
            if (rebuilt != null) {
                catalog.set(rebuilt);
                return rebuilt;
            }
            // Serving a stale table beats serving the estimate table: a day-old list price is far
            // closer to the invoice than a per-family constant with no region in it.
            if (existing != null) {
                log.warn("[gcp-pricing] Catalog refresh failed; continuing with the table built at {}",
                        java.time.Instant.ofEpochMilli(existing.builtAtMs()));
            }
            return existing;
        }
    }

    private CatalogSnapshot buildCatalogSnapshot(String gcpProjectId) {
        Optional<CloudCatalogClient> clientOpt = gcpClientProvider.getCloudCatalogClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            log.warn("[gcp-pricing] No Cloud Catalog client for project {}; Compute Engine prices "
                    + "will fall back to per-family estimates", gcpProjectId);
            return null;
        }

        Map<String, Map<String, Map<Boolean, FamilyRates>>> byRegion = new HashMap<>();
        int skusRead = 0;
        int ratesResolved = 0;

        try (CloudCatalogClient client = clientOpt.get()) {
            ListSkusRequest request = ListSkusRequest.newBuilder()
                    .setParent(COMPUTE_ENGINE_SERVICE)
                    .setCurrencyCode(CATALOG_CURRENCY)
                    .setPageSize(5000)
                    .build();

            for (Sku sku : client.listSkus(request).iterateAll()) {
                skusRead++;
                if (applySku(sku, byRegion)) {
                    ratesResolved++;
                }
            }
        } catch (Exception e) {
            log.warn("[gcp-pricing] Could not read the Compute Engine catalog for project {}: {}",
                    gcpProjectId, e.getMessage());
            return null;
        }

        if (ratesResolved == 0) {
            log.warn("[gcp-pricing] Read {} Compute Engine SKUs but matched none as a core or RAM "
                    + "rate. The catalog's description format has probably changed — prices will "
                    + "fall back to per-family estimates.", skusRead);
            return null;
        }

        log.info("[gcp-pricing] Built Compute Engine rate table from {} SKUs: {} rates across {} regions",
                skusRead, ratesResolved, byRegion.size());
        return new CatalogSnapshot(byRegion, System.currentTimeMillis());
    }

    /**
     * Folds one SKU into the rate table.
     *
     * @return whether the SKU contributed a rate
     */
    private boolean applySku(Sku sku, Map<String, Map<String, Map<Boolean, FamilyRates>>> byRegion) {
        if (!"Compute".equalsIgnoreCase(sku.getCategory().getResourceFamily())) {
            return false;
        }

        SkuMeter meter = classifySku(sku);
        if (meter == null) {
            return false;
        }

        Double rate = unitPriceUsd(sku);
        if (rate == null) {
            return false;
        }

        boolean applied = false;
        for (String skuRegion : sku.getServiceRegionsList()) {
            String region = skuRegion.toLowerCase(Locale.ROOT);
            Map<Boolean, FamilyRates> byUsage = byRegion
                    .computeIfAbsent(region, r -> new HashMap<>())
                    .computeIfAbsent(meter.family(), f -> new HashMap<>());

            FamilyRates current = byUsage.getOrDefault(meter.spot(), new FamilyRates(null, null));
            byUsage.put(meter.spot(), meter.ram()
                    ? current.withRam(rate)
                    : current.withCore(rate));
            applied = true;
        }
        return applied;
    }

    /**
     * A Compute Engine core or RAM SKU, reduced to what the rate table needs.
     *
     * @param family catalog family token, upper case, e.g. {@code N2}, {@code C2D}
     * @param ram    whether this is the RAM meter rather than the core meter
     * @param spot   whether this is the spot/preemptible rate
     */
    private record SkuMeter(String family, boolean ram, boolean spot) {
    }

    /**
     * Descriptions the rate table must not absorb.
     *
     * <p>Custom and extended-memory SKUs are priced differently from predefined ones; sole-tenancy
     * carries a premium; commitment and reservation SKUs are not on-demand prices at all. Letting
     * any of these overwrite a family's rate would quietly mis-price every machine in that family.
     */
    private static final Pattern EXCLUDED_DESCRIPTION = Pattern.compile(
            "(?i)\\b(custom|extended|sole tenancy|sole-tenancy|commitment|reserved|reservation|"
                    + "premium|licensing|license|gpu|tpu|local ssd|storage|snapshot|network)\\b");

    /**
     * Matches the core and RAM instance meters, capturing the family token.
     *
     * <p>Handles the published forms: {@code "N2 Instance Core running in Mumbai"},
     * {@code "N1 Predefined Instance Ram running in Americas"},
     * {@code "N2D AMD Instance Core running in Mumbai"},
     * {@code "T2A Arm Instance Core running in Mumbai"} and the
     * {@code "Spot Preemptible ..."} variants of each.
     */
    private static final Pattern INSTANCE_METER = Pattern.compile(
            "(?i)^(?<spot>spot\\s+preemptible\\s+|preemptible\\s+)?"
                    + "(?<family>[a-z]\\d+[a-z]?)"
                    + "(?:\\s+(?:amd|arm|intel))?"
                    + "(?:\\s+predefined)?"
                    + "\\s+instance\\s+(?<meter>core|ram)\\s+running\\s+in\\s+.+$");

    private SkuMeter classifySku(Sku sku) {
        String description = sku.getDescription();
        if (description == null || description.isBlank()) {
            return null;
        }
        if (EXCLUDED_DESCRIPTION.matcher(description).find()) {
            return null;
        }

        Matcher matcher = INSTANCE_METER.matcher(description.trim());
        if (!matcher.matches()) {
            return null;
        }

        boolean ram = "ram".equalsIgnoreCase(matcher.group("meter"));

        // The usage unit is the authoritative check that this meter is what the description says:
        // cores are billed per hour, RAM per gibibyte-hour.
        String usageUnit = sku.getPricingInfoCount() == 0
                ? ""
                : sku.getPricingInfo(sku.getPricingInfoCount() - 1).getPricingExpression().getUsageUnit();
        String expectedUnit = ram ? RAM_USAGE_UNIT : CORE_USAGE_UNIT;
        if (!expectedUnit.equals(usageUnit)) {
            log.debug("[gcp-pricing] Skipping SKU '{}': expected usage unit {} for a {} meter but "
                    + "found '{}'", description, expectedUnit, ram ? "RAM" : "core", usageUnit);
            return null;
        }

        boolean spot = matcher.group("spot") != null
                || "Preemptible".equalsIgnoreCase(sku.getCategory().getUsageType());

        // Anything that is neither on-demand nor preemptible is a commitment rate.
        String usageType = sku.getCategory().getUsageType();
        if (!spot && !"OnDemand".equalsIgnoreCase(usageType)) {
            return null;
        }

        return new SkuMeter(matcher.group("family").toUpperCase(Locale.ROOT), ram, spot);
    }

    /**
     * The USD unit price of a SKU's base tier, or null when it cannot be read.
     *
     * <p>Takes the most recent pricing entry and its base tier — the rate that applies from zero
     * usage. Compute core and RAM meters are not tiered, so there is a single tier in practice; if
     * that ever changes, the base tier is the conservative choice because higher tiers are
     * discounts a customer may not reach.
     */
    private Double unitPriceUsd(Sku sku) {
        if (sku.getPricingInfoCount() == 0) {
            return null;
        }
        PricingExpression expression = sku
                .getPricingInfo(sku.getPricingInfoCount() - 1)
                .getPricingExpression();
        if (expression.getTieredRatesCount() == 0) {
            return null;
        }

        PricingExpression.TierRate base = expression.getTieredRatesList().stream()
                .min(java.util.Comparator.comparingDouble(PricingExpression.TierRate::getStartUsageAmount))
                .orElse(null);
        if (base == null) {
            return null;
        }

        String currency = base.getUnitPrice().getCurrencyCode();
        if (!CATALOG_CURRENCY.equalsIgnoreCase(currency)) {
            log.debug("[gcp-pricing] Skipping SKU '{}': priced in {} rather than {}",
                    sku.getDescription(), currency, CATALOG_CURRENCY);
            return null;
        }

        double price = base.getUnitPrice().getUnits() + base.getUnitPrice().getNanos() / 1_000_000_000.0;
        // A zero rate is a free tier or a placeholder, not a price. Accepting it would silently
        // make an entire family look free.
        return price > 0 ? price : null;
    }

    // ── Estimate table ────────────────────────────────────────────────────────

    /**
     * Per-vCPU-hour and per-GiB-hour estimates used only when the catalog is unreachable.
     *
     * <p>US list prices, with no regional adjustment. The four-bucket regional multiplier this
     * replaces implied a precision it did not have — it grouped 40-odd regions into US, Europe,
     * Asia and Australia and applied 1.00/1.05/1.10/1.15. Rather than keep inventing a region
     * factor, results from this table are marked {@link PriceSource#ESTIMATE_TABLE} so a caller
     * can decline to present them as prices.
     */
    private static final Map<String, double[]> ESTIMATE_RATES = Map.ofEntries(
            // family -> { onDemandCorePerHour, onDemandRamPerGibHour, spotCore, spotRam }
            Map.entry("E2", new double[] { 0.021811, 0.002923, 0.006543, 0.000877 }),
            Map.entry("N1", new double[] { 0.031611, 0.004237, 0.006655, 0.000892 }),
            Map.entry("N2", new double[] { 0.031611, 0.004237, 0.007650, 0.001025 }),
            Map.entry("N2D", new double[] { 0.027502, 0.003686, 0.006655, 0.000892 }),
            Map.entry("N4", new double[] { 0.034460, 0.004618, 0.013784, 0.001847 }),
            Map.entry("C2", new double[] { 0.033911, 0.004542, 0.008205, 0.001099 }),
            Map.entry("C2D", new double[] { 0.029500, 0.003951, 0.007138, 0.000956 }),
            Map.entry("C3", new double[] { 0.035392, 0.004745, 0.014157, 0.001898 }),
            Map.entry("C3D", new double[] { 0.030805, 0.004129, 0.012322, 0.001652 }),
            Map.entry("C4", new double[] { 0.036889, 0.004946, 0.014756, 0.001978 }),
            Map.entry("T2D", new double[] { 0.027502, 0.003686, 0.006875, 0.000922 }),
            Map.entry("T2A", new double[] { 0.025000, 0.003350, 0.006250, 0.000838 }),
            Map.entry("M1", new double[] { 0.031611, 0.005271, 0.006655, 0.001109 }),
            Map.entry("M2", new double[] { 0.031611, 0.005271, 0.006655, 0.001109 }),
            Map.entry("M3", new double[] { 0.034000, 0.005600, 0.008500, 0.001400 }));

    private PricingInfo estimateFromTable(String machineType, MachineTypeSpecs specs) {
        double[] rates = ESTIMATE_RATES.get(specs.catalogFamily());
        if (rates == null) {
            log.warn("[gcp-pricing] No estimate for family {} ({}) and the catalog is unavailable — "
                    + "reporting unpriced", specs.catalogFamily(), machineType);
            return PricingInfo.unavailable("no catalog rate and no estimate for family "
                    + specs.catalogFamily());
        }

        double onDemand = specs.vCpus() * rates[0] + specs.memoryGb() * rates[1];
        double spot = specs.vCpus() * rates[2] + specs.memoryGb() * rates[3];

        log.debug("[gcp-pricing] Estimated {} at ${}/hr on-demand from the fallback table (region "
                + "not applied)", machineType, String.format("%.5f", onDemand));

        return PricingInfo.priced(onDemand, spot, PriceSource.ESTIMATE_TABLE,
                "Cloud Billing Catalog unavailable; US list-price estimate with no regional "
                        + "adjustment applied");
    }

    // ── Machine type shapes ───────────────────────────────────────────────────

    /**
     * vCPU count and memory for a machine type.
     *
     * @param catalogFamily upper-case family token used in catalog descriptions, e.g. {@code N2}
     * @param vCpus         vCPU count; fractional for the shared-core types
     * @param memoryGb      memory in GiB
     * @param recognised    whether the shape is known, as opposed to a guess
     */
    record MachineTypeSpecs(String catalogFamily, double vCpus, double memoryGb, boolean recognised) {

        static MachineTypeSpecs unknown(String family) {
            return new MachineTypeSpecs(family == null ? "" : family.toUpperCase(Locale.ROOT),
                    0, 0, false);
        }
    }

    /**
     * Fixed shapes that do not follow the {@code family-shape-size} arithmetic.
     *
     * <p>The shared-core types matter most: the previous table recorded {@code e2-micro},
     * {@code e2-small} and {@code e2-medium} as 2 vCPUs each, when they are 0.25, 0.5 and 1. That
     * overstated an {@code e2-micro} by eight times. The memory-optimised shapes matter for the
     * opposite reason — {@code m1-ultramem-40} carries 961 GiB, which no per-vCPU ratio predicts.
     */
    private static final Map<String, double[]> FIXED_SHAPES = Map.ofEntries(
            // name -> { vCpus, memoryGb }
            Map.entry("e2-micro", new double[] { 0.25, 1.0 }),
            Map.entry("e2-small", new double[] { 0.5, 2.0 }),
            Map.entry("e2-medium", new double[] { 1.0, 4.0 }),
            Map.entry("f1-micro", new double[] { 0.2, 0.6 }),
            Map.entry("g1-small", new double[] { 0.5, 1.7 }),
            Map.entry("m1-ultramem-40", new double[] { 40, 961.0 }),
            Map.entry("m1-ultramem-80", new double[] { 80, 1922.0 }),
            Map.entry("m1-ultramem-160", new double[] { 160, 3844.0 }),
            Map.entry("m1-megamem-96", new double[] { 96, 1433.6 }),
            Map.entry("m2-ultramem-208", new double[] { 208, 5888.0 }),
            Map.entry("m2-ultramem-416", new double[] { 416, 11776.0 }),
            Map.entry("m2-megamem-416", new double[] { 416, 5888.0 }),
            Map.entry("m2-hypermem-416", new double[] { 416, 8832.0 }));

    /** {@code n2-custom-4-16384} and {@code n2d-custom-2-4096}: vCPUs and memory in MiB. */
    private static final Pattern CUSTOM_TYPE = Pattern.compile(
            "^(?<family>[a-z]\\d+[a-z]?)-custom-(?<vcpus>\\d+)-(?<memoryMib>\\d+)$");

    /** {@code n2-standard-8}, {@code c2d-highcpu-16}, and the {@code -lssd}/{@code -metal} variants. */
    private static final Pattern SHAPED_TYPE = Pattern.compile(
            "^(?<family>[a-z]\\d+[a-z]?)-(?<shape>standard|highmem|highcpu|ultramem|megamem|hypermem)"
                    + "-(?<size>\\d+)(?:-(?:lssd|metal))?$");

    /**
     * Memory per vCPU, in GiB, by family and shape.
     *
     * <p>The previous implementation applied N1's ratios to every family: 3.75 GiB per vCPU for
     * standard, 6.5 for highmem, 0.9 for highcpu. Only N1 uses those. Every other family's
     * standard shape is 4 GiB per vCPU, so an {@code n2-standard-8} was costed as 30 GiB of RAM
     * instead of 32.
     */
    private static double memoryPerVcpu(String family, String shape) {
        boolean isN1 = "N1".equals(family);
        return switch (shape) {
            case "standard" -> isN1 ? 3.75 : 4.0;
            case "highmem" -> isN1 ? 6.5 : 8.0;
            case "highcpu" -> {
                if (isN1) {
                    yield 0.9;
                }
                // The dense-compute families give twice as much memory per vCPU as N2/E2 do.
                yield switch (family) {
                    case "C2D", "C3", "C3D", "C4" -> 2.0;
                    default -> 1.0;
                };
            }
            default -> -1.0;
        };
    }

    /**
     * Resolves a machine type name to its vCPU count and memory.
     *
     * <p>Package-private and static so the shape arithmetic can be tested without a catalog.
     */
    static MachineTypeSpecs parseMachineType(String machineType) {
        if (machineType == null || machineType.isBlank()) {
            return MachineTypeSpecs.unknown(null);
        }
        String name = machineType.trim().toLowerCase(Locale.ROOT);

        // A fully-qualified machine type URL may arrive instead of a bare name.
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }

        String family = name.contains("-")
                ? name.substring(0, name.indexOf('-')).toUpperCase(Locale.ROOT)
                : name.toUpperCase(Locale.ROOT);

        double[] fixed = FIXED_SHAPES.get(name);
        if (fixed != null) {
            return new MachineTypeSpecs(family, fixed[0], fixed[1], true);
        }

        Matcher custom = CUSTOM_TYPE.matcher(name);
        if (custom.matches()) {
            // Custom shapes state their own size, so no ratio is involved.
            return new MachineTypeSpecs(
                    custom.group("family").toUpperCase(Locale.ROOT),
                    Integer.parseInt(custom.group("vcpus")),
                    Integer.parseInt(custom.group("memoryMib")) / 1024.0,
                    true);
        }

        Matcher shaped = SHAPED_TYPE.matcher(name);
        if (shaped.matches()) {
            String shapedFamily = shaped.group("family").toUpperCase(Locale.ROOT);
            String shape = shaped.group("shape");
            int size = Integer.parseInt(shaped.group("size"));
            double perVcpu = memoryPerVcpu(shapedFamily, shape);
            if (perVcpu > 0) {
                return new MachineTypeSpecs(shapedFamily, size, size * perVcpu, true);
            }
            // ultramem/megamem/hypermem outside the fixed table: the ratio varies by size, so
            // there is nothing safe to compute.
            return MachineTypeSpecs.unknown(shapedFamily);
        }

        return MachineTypeSpecs.unknown(family);
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    /** Savings percentage between two rates. Returns 0 when the baseline is not positive. */
    public double calculateSavingsPercentage(double onDemandPrice, double spotPrice) {
        if (onDemandPrice <= 0) {
            return 0;
        }
        return ((onDemandPrice - spotPrice) / onDemandPrice) * 100;
    }

    /** Drops both the resolved prices and the catalog rate table. */
    public void clearCache() {
        priceCache.clear();
        catalog.set(null);
        log.info("[gcp-pricing] Cache cleared");
    }

    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        CatalogSnapshot snapshot = catalog.get();
        stats.put("resolvedPrices", priceCache.size());
        stats.put("catalogLoaded", snapshot != null);
        stats.put("catalogRegions", snapshot == null ? 0 : snapshot.byRegion().size());
        stats.put("catalogBuiltAt", snapshot == null
                ? null
                : java.time.Instant.ofEpochMilli(snapshot.builtAtMs()).toString());
        stats.put("catalogTtlHours", CATALOG_TTL_MS / (60 * 60 * 1000));
        stats.put("currency", CATALOG_CURRENCY);
        return stats;
    }

    /**
     * A machine type's hourly on-demand and spot list prices, in USD.
     *
     * <p>Carries where the figures came from, so a caller can tell a catalog list price from an
     * estimate and from "not known". The service used to return INR-denominated doubles with no
     * way to tell those three cases apart.
     */
    public static class PricingInfo {

        private final double onDemandPrice;
        private final double spotPrice;
        private final boolean spotAvailable;
        private final PriceSource source;
        private final String note;

        private PricingInfo(double onDemandPrice, double spotPrice, boolean spotAvailable,
                PriceSource source, String note) {
            this.onDemandPrice = onDemandPrice;
            this.spotPrice = spotPrice;
            this.spotAvailable = spotAvailable;
            this.source = source;
            this.note = note;
        }

        static PricingInfo priced(double onDemand, double spot, PriceSource source, String note) {
            return new PricingInfo(onDemand, spot, true, source, note);
        }

        static PricingInfo onDemandOnly(double onDemand, PriceSource source, String note) {
            // Spot equals on-demand so any savings arithmetic yields zero rather than a windfall.
            return new PricingInfo(onDemand, onDemand, false, source, note);
        }

        static PricingInfo unavailable(String note) {
            return new PricingInfo(0, 0, false, PriceSource.UNAVAILABLE, note);
        }

        /** USD per hour. Zero when {@link #isPriced()} is false. */
        public double getOnDemandPrice() {
            return onDemandPrice;
        }

        /** USD per hour. Equal to the on-demand rate when spot is not offered. */
        public double getSpotPrice() {
            return spotPrice;
        }

        /** Whether these figures are usable at all. */
        public boolean isPriced() {
            return source != PriceSource.UNAVAILABLE;
        }

        /** Whether a spot rate was actually published, as opposed to being mirrored from on-demand. */
        public boolean isSpotAvailable() {
            return spotAvailable;
        }

        public PriceSource getSource() {
            return source;
        }

        /** Why the price is estimated or missing, for display. Null when nothing needs saying. */
        public String getNote() {
            return note;
        }

        public String getCurrency() {
            return CATALOG_CURRENCY;
        }

        public double getSavings() {
            return onDemandPrice - spotPrice;
        }

        public double getSavingsPercentage() {
            if (onDemandPrice <= 0) {
                return 0;
            }
            return ((onDemandPrice - spotPrice) / onDemandPrice) * 100;
        }
    }
}
