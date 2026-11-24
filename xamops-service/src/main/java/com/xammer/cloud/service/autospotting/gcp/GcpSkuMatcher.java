package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The versioned SKU matching table {@code gcp-sku-match-v1} (design-gcp.md §6.4): maps
 * {@code (series, resource, model)} to a Cloud Billing Catalog SKU. A row is: the description a
 * core SKU and a RAM SKU start with (after the {@code Spot Preemptible } prefix of a Spot SKU), plus,
 * for M2, the upgrade-premium core and RAM SKUs. The filter is
 * {@code category.resourceFamily=Compute}, {@code category.usageType} ({@code OnDemand} for standard,
 * {@code Preemptible} for Spot) and {@code serviceRegions} containing the region.
 *
 * <p>No fallback: a series with no row, a custom or extended-memory type with no custom row, a SKU
 * that does not match, or a match that is ambiguous (different prices) is "not priced", never a guess.
 * A new family gets a row here and a refreshed fixture (V-GCP-09).
 */
public final class GcpSkuMatcher {
    public static final String VERSION = "gcp-sku-match-v1";
    static final String SPOT_PREFIX = "spot preemptible ";

    /** Description starts for a series. {@code premium*} is the M2 upgrade premium (null elsewhere). */
    record Row(String core, String ram, String customCore, String customRam, String premiumCore, String premiumRam) {
    }

    private static final Map<String, Row> ROWS = Map.of(
            "e2", new Row("e2 instance core running in", "e2 instance ram running in",
                    "e2 custom instance core running in", "e2 custom instance ram running in", null, null),
            "n1", new Row("n1 predefined instance core running in", "n1 predefined instance ram running in",
                    "custom instance core running in", "custom instance ram running in", null, null),
            "n2", new Row("n2 instance core running in", "n2 instance ram running in",
                    "n2 custom instance core running in", "n2 custom instance ram running in", null, null),
            "n2d", new Row("n2d amd instance core running in", "n2d amd instance ram running in",
                    "n2d amd custom instance core running in", "n2d amd custom instance ram running in", null, null),
            "c2", new Row("compute optimized core running in", "compute optimized ram running in",
                    null, null, null, null),
            "m1", new Row("memory-optimized instance core running in", "memory-optimized instance ram running in",
                    null, null, null, null),
            "m2", new Row("memory-optimized instance core running in", "memory-optimized instance ram running in",
                    null, null,
                    "memory optimized upgrade premium for memory-optimized instance core running in",
                    "memory optimized upgrade premium for memory-optimized instance ram running in"));

    private GcpSkuMatcher() {
    }

    /** The series the matcher prices (an accepted series must match in the fixture, V-GCP-09). */
    public static Set<String> acceptedSeries() {
        return ROWS.keySet();
    }

    public static boolean accepts(String series) {
        return series != null && ROWS.containsKey(series);
    }

    /** Per-hour unit prices of one shape's resources, or empty when any required SKU has no unambiguous match. */
    public record Rate(BigDecimal perVcpuHour, BigDecimal perGibHour) {
    }

    /**
     * The core and RAM price (premium included for M2) per hour for {@code series} in {@code region}.
     *
     * @param custom true for a custom machine type (priced from the custom row, else not priced)
     * @param spot   true for Spot ({@code Preemptible} usage type)
     */
    public static Optional<Rate> match(List<JsonNode> skus, String series, String region, boolean custom, boolean spot) {
        Row row = ROWS.get(series);
        if (row == null) {
            return Optional.empty();
        }
        String coreStart = custom ? row.customCore() : row.core();
        String ramStart = custom ? row.customRam() : row.ram();
        if (coreStart == null || ramStart == null) {
            return Optional.empty();
        }
        Optional<BigDecimal> core = price(skus, coreStart, region, spot, "h");
        Optional<BigDecimal> ram = price(skus, ramStart, region, spot, "GiBy.h");
        if (core.isEmpty() || ram.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal vcpu = core.get();
        BigDecimal gib = ram.get();
        if (row.premiumCore() != null && !custom) {
            Optional<BigDecimal> pc = price(skus, row.premiumCore(), region, spot, "h");
            Optional<BigDecimal> pr = price(skus, row.premiumRam(), region, spot, "GiBy.h");
            if (pc.isEmpty() || pr.isEmpty()) {
                return Optional.empty();
            }
            vcpu = vcpu.add(pc.get());
            gib = gib.add(pr.get());
        }
        return Optional.of(new Rate(vcpu, gib));
    }

    /**
     * The newest {@code pricingInfo.effectiveTime} of the core and RAM SKUs {@link #match} prices a shape from, or
     * empty when a SKU states none (the caller then records the price as observed, not as effective).
     */
    public static Optional<String> effectiveTime(List<JsonNode> skus, String series, String region, boolean custom,
                                                 boolean spot) {
        Row row = ROWS.get(series);
        if (row == null) {
            return Optional.empty();
        }
        String coreStart = custom ? row.customCore() : row.core();
        String ramStart = custom ? row.customRam() : row.ram();
        if (coreStart == null || ramStart == null) {
            return Optional.empty();
        }
        List<JsonNode> all = new ArrayList<>(matching(skus, coreStart, region, spot, "h"));
        all.addAll(matching(skus, ramStart, region, spot, "GiBy.h"));
        if (row.premiumCore() != null && !custom) {
            all.addAll(matching(skus, row.premiumCore(), region, spot, "h"));
            all.addAll(matching(skus, row.premiumRam(), region, spot, "GiBy.h"));
        }
        String newest = null;
        for (JsonNode sku : all) {
            String t = sku.path("pricingInfo").path(0).path("effectiveTime").asText(null);
            if (t == null || t.isBlank()) {
                return Optional.empty();
            }
            if (newest == null || java.time.Instant.parse(t).isAfter(java.time.Instant.parse(newest))) {
                newest = t;
            }
        }
        return Optional.ofNullable(newest);
    }

    private static Optional<BigDecimal> price(List<JsonNode> skus, String descriptionStart, String region,
                                              boolean spot, String usageUnit) {
        List<BigDecimal> found = new ArrayList<>();
        for (JsonNode sku : matching(skus, descriptionStart, region, spot, usageUnit)) {
            found.add(unitPrice(sku.path("pricingInfo").path(0).path("pricingExpression").path("tieredRates")
                    .path(0).path("unitPrice")));
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal first = found.get(0);
        for (BigDecimal p : found) {
            if (p.compareTo(first) != 0) {
                return Optional.empty(); // ambiguous: never pick one
            }
        }
        return Optional.of(first);
    }

    private static List<JsonNode> matching(List<JsonNode> skus, String descriptionStart, String region, boolean spot,
                                           String usageUnit) {
        List<JsonNode> found = new ArrayList<>();
        String usageType = spot ? "Preemptible" : "OnDemand";
        for (JsonNode sku : skus) {
            JsonNode cat = sku.path("category");
            if (!"Compute".equals(cat.path("resourceFamily").asText()) || !usageType.equals(cat.path("usageType").asText())) {
                continue;
            }
            if (!regionMatches(sku.path("serviceRegions"), region)) {
                continue;
            }
            String d = sku.path("description").asText("").toLowerCase(Locale.ROOT);
            if (d.startsWith(SPOT_PREFIX)) {
                d = d.substring(SPOT_PREFIX.length());
            } else if (spot) {
                continue;
            }
            if (!d.startsWith(descriptionStart)) {
                continue;
            }
            JsonNode expr = sku.path("pricingInfo").path(0).path("pricingExpression");
            if (!usageUnit.equals(expr.path("usageUnit").asText())) {
                continue;
            }
            JsonNode unit = expr.path("tieredRates").path(0).path("unitPrice");
            if (!"USD".equals(unit.path("currencyCode").asText())) {
                continue;
            }
            found.add(sku);
        }
        return found;
    }

    private static boolean regionMatches(JsonNode regions, String region) {
        for (JsonNode r : regions) {
            if (region.equals(r.asText())) {
                return true;
            }
        }
        return false;
    }

    /** {@code units + nanos / 10^9}, exactly. */
    static BigDecimal unitPrice(JsonNode unit) {
        BigDecimal units = new BigDecimal(unit.path("units").asText("0"));
        BigDecimal nanos = BigDecimal.valueOf(unit.path("nanos").asLong(0)).movePointLeft(9);
        return units.add(nanos);
    }
}
