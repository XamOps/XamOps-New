package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks up live Azure list prices from the public Azure Retail Prices API.
 *
 * <p>Before this existed, every Azure cost figure in the platform was a hardcoded
 * constant: an 18-entry INR VM table with no region dimension, a four-entry disk table,
 * and sixteen flat per-category waste constants. A stopped {@code Standard_E16s_v5} was
 * reported as ₹1,500/month against its own table's ₹84,000 — a 56x understatement.
 *
 * <p>The Retail Prices API needs no authentication and no subscription context, so it can
 * be queried directly. Results are cached in-process with a TTL because list prices change
 * infrequently, and every lookup returns {@link Optional} so callers can distinguish
 * "unknown" from "free" rather than defaulting to a misleading number.
 *
 * <p>Prices are list prices in USD. They deliberately exclude any Reserved Instance,
 * Savings Plan or Azure Hybrid Benefit discount the customer may already have, so savings
 * derived from them are an upper bound. Callers presenting these figures should say so.
 */
@Service
public class AzureRetailPricingService {

    private static final Logger logger = LoggerFactory.getLogger(AzureRetailPricingService.class);

    private static final String RETAIL_PRICES_ENDPOINT = "https://prices.azure.com/api/retail/prices";
    private static final String API_VERSION = "2023-01-01-preview";

    /** Azure bills a 730-hour month. */
    public static final double HOURS_PER_MONTH = 730.0;

    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CachedPrice> cache = new ConcurrentHashMap<>();

    public AzureRetailPricingService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Monthly list price for a VM size in a region, excluding OS licensing.
     *
     * @param armSkuName Azure VM size, e.g. {@code Standard_D4s_v5}
     * @param region     Azure region, e.g. {@code eastus}
     * @return monthly USD price, or empty when it cannot be determined
     */
    public Optional<Double> getVmMonthlyPriceUsd(String armSkuName, String region) {
        if (armSkuName == null || armSkuName.isBlank() || region == null || region.isBlank()) {
            return Optional.empty();
        }
        // Consumption + Linux (no "Windows" in productName) gives the compute-only rate.
        String filter = String.format(
                "serviceName eq 'Virtual Machines' and armSkuName eq '%s' and armRegionName eq '%s' "
                        + "and priceType eq 'Consumption'",
                escape(armSkuName), escape(region));

        return hourlyPrice("vm:" + armSkuName + ":" + region, filter, true)
                .map(hourly -> hourly * HOURS_PER_MONTH);
    }

    /**
     * Monthly list price for a managed disk SKU of a given size.
     *
     * <p>Managed disks are billed per provisioned tier (P10, E20, S30...), not per GB, so
     * this returns the tier price rather than a per-GB rate multiplied by size.
     *
     * @param skuName Azure disk SKU, e.g. {@code Premium_LRS}
     * @param sizeGb  provisioned size in GB
     * @param region  Azure region
     * @return monthly USD price, or empty when it cannot be determined
     */
    public Optional<Double> getManagedDiskMonthlyPriceUsd(String skuName, int sizeGb, String region) {
        if (skuName == null || skuName.isBlank() || region == null || region.isBlank() || sizeGb <= 0) {
            return Optional.empty();
        }
        String tierSku = managedDiskTier(skuName, sizeGb);
        if (tierSku == null) {
            return Optional.empty();
        }

        String cacheKey = "disk:" + tierSku + ":" + region;
        CachedPrice cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.price();
        }

        String filter = String.format(
                "serviceName eq 'Storage' and armRegionName eq '%s' and priceType eq 'Consumption' "
                        + "and skuName eq '%s'",
                escape(region), escape(tierSku));

        // A disk SKU carries several meters: the capacity charge ("P10 LRS Disk"), a mount
        // charge ("P10 LRS Disk Mount") and a per-operation charge ("P10 LRS Disk
        // Operations"). Only the first is the monthly capacity price. Taking the cheapest
        // across all of them — as a generic min would — returns the $0.0005/10K operations
        // rate instead.
        PriceLookup monthly = lowestPriceForMeter(filter, tierSku + " Disk");
        // Never cache a failed call for the 24h TTL — see hourlyPrice.
        if (monthly.succeeded()) {
            cache.put(cacheKey, new CachedPrice(monthly.price(), Instant.now()));
        }
        return monthly.price();
    }

    /**
     * Monthly list price for a public IPv4 address.
     *
     * <p>Since February 2024 Azure bills all public IPv4 addresses, whether associated or
     * not, so this applies to attached addresses too.
     */
    public Optional<Double> getPublicIpMonthlyPriceUsd(String region) {
        String filter = String.format(
                "serviceName eq 'Virtual Network' and armRegionName eq '%s' and priceType eq 'Consumption' "
                        + "and contains(meterName, 'Static Public IP')",
                escape(region));
        return hourlyPrice("pip:" + region, filter, true)
                .map(hourly -> hourly * HOURS_PER_MONTH);
    }

    /**
     * Maps a disk SKU and provisioned size onto the Retail Prices {@code skuName}.
     *
     * <p>Azure rounds a provisioned size up to the next tier, so a 200 GB Premium disk is
     * billed as P15 (256 GB).
     *
     * <p>The returned value is the {@code skuName} exactly as the catalog publishes it —
     * {@code "P15 LRS"}, with no {@code " Disk"} suffix. Verified against the live API: the
     * suffix belongs to {@code meterName} ({@code "P15 LRS Disk"}), not {@code skuName}, so
     * including it here matched nothing and every disk silently fell back to a flat estimate.
     *
     * @return catalog SKU name such as {@code P15 LRS}, or null for an unmapped family
     */
    public String managedDiskTier(String skuName, int sizeGb) {
        String prefix;
        String redundancy = skuName.contains("ZRS") ? "ZRS" : "LRS";
        if (skuName.startsWith("Premium_LRS") || skuName.startsWith("Premium_ZRS")) {
            prefix = "P";
        } else if (skuName.startsWith("StandardSSD_LRS") || skuName.startsWith("StandardSSD_ZRS")) {
            prefix = "E";
        } else if (skuName.startsWith("Standard_LRS")) {
            prefix = "S";
        } else {
            // Premium_V2 and UltraSSD are billed on provisioned IOPS and throughput as well
            // as capacity, so a capacity-only tier lookup would be wrong.
            return null;
        }

        int[] sizes = { 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32767 };
        String[] tiers = { "1", "2", "3", "4", "6", "10", "15", "20", "30", "40", "50", "60", "70", "80" };
        for (int i = 0; i < sizes.length; i++) {
            if (sizeGb <= sizes[i]) {
                return prefix + tiers[i] + " " + redundancy;
            }
        }
        return null;
    }

    /**
     * Queries the catalog and returns the lowest price among rows whose {@code meterName}
     * matches exactly.
     *
     * <p>Needed because one SKU exposes several meters and only one of them is the charge
     * being asked about.
     */
    private PriceLookup lowestPriceForMeter(String filter, String meterName) {
        try {
            JsonNode items = fetchItems(filter);
            if (items == null) {
                return PriceLookup.failed();
            }
            OptionalDouble lowest = java.util.stream.StreamSupport
                    .stream(items.spliterator(), false)
                    .filter(item -> meterName.equals(item.path("meterName").asText("")))
                    .mapToDouble(item -> item.path("retailPrice").asDouble(0.0))
                    .filter(price -> price > 0)
                    .min();
            return PriceLookup.answered(
                    lowest.isPresent() ? Optional.of(lowest.getAsDouble()) : Optional.empty());
        } catch (Exception e) {
            logger.warn("Azure Retail Prices meter lookup failed for '{}': {}", meterName, e.getMessage());
            return PriceLookup.failed();
        }
    }

    /**
     * Queries the Retail Prices API and returns the lowest matching unit price.
     *
     * @param cacheKey    in-process cache key
     * @param filter      OData filter expression
     * @param excludeWindows drop Windows-licensed SKUs, which carry an OS surcharge
     */
    private Optional<Double> hourlyPrice(String cacheKey, String filter, boolean excludeWindows) {
        CachedPrice cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.price();
        }

        PriceLookup result = queryLowestPrice(filter, excludeWindows);
        // Only remember an answer the API actually gave. Caching a failure for the full 24h TTL
        // meant a single HTTP 429 during a scan pinned the FALLBACK_* constants for a whole day,
        // with nothing but one warn line to show the figures were not live.
        if (result.succeeded()) {
            cache.put(cacheKey, new CachedPrice(result.price(), Instant.now()));
        }
        return result.price();
    }

    /**
     * Outcome of a Retail Prices query.
     *
     * <p>{@code succeeded} separates "the API answered and there is no matching SKU" — a real
     * answer worth caching — from "the call failed", which must not be cached as though it
     * were a priced result.
     */
    private record PriceLookup(Optional<Double> price, boolean succeeded) {
        static PriceLookup answered(Optional<Double> price) {
            return new PriceLookup(price, true);
        }

        static PriceLookup failed() {
            return new PriceLookup(Optional.empty(), false);
        }
    }

    private PriceLookup queryLowestPrice(String filter, boolean excludeWindows) {
        try {
            JsonNode items = fetchItems(filter);
            if (items == null) {
                // fetchItems logs and returns null on a non-2xx, which is a failure rather
                // than an empty result set.
                return PriceLookup.failed();
            }

            OptionalDouble lowest = java.util.stream.StreamSupport
                    .stream(items.spliterator(), false)
                    .filter(item -> !excludeWindows
                            || !item.path("productName").asText("").contains("Windows"))
                    // Spot and low-priority meters are not comparable to the standing rate.
                    .filter(item -> !item.path("skuName").asText("").contains("Spot"))
                    .filter(item -> !item.path("skuName").asText("").contains("Low Priority"))
                    .mapToDouble(item -> item.path("retailPrice").asDouble(0.0))
                    .filter(price -> price > 0)
                    .min();

            return PriceLookup.answered(
                    lowest.isPresent() ? Optional.of(lowest.getAsDouble()) : Optional.empty());

        } catch (Exception e) {
            logger.warn("Azure Retail Prices lookup failed for filter '{}': {}", filter, e.getMessage());
            return PriceLookup.failed();
        }
    }

    /**
     * Executes a Retail Prices query and returns the {@code Items} array.
     *
     * @return the array node, or null when the call failed or returned nothing
     */
    private JsonNode fetchItems(String filter) throws Exception {
        String url = RETAIL_PRICES_ENDPOINT
                + "?api-version=" + API_VERSION
                + "&currencyCode=USD"
                + "&$filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            logger.warn("Azure Retail Prices API returned HTTP {} for filter: {}",
                    response.statusCode(), filter);
            return null;
        }

        JsonNode items = objectMapper.readTree(response.body()).path("Items");
        if (!items.isArray() || items.isEmpty()) {
            logger.debug("Azure Retail Prices API returned no items for filter: {}", filter);
            return null;
        }
        return items;
    }

    /** Escapes single quotes for an OData string literal. */
    private String escape(String value) {
        return value.replace("'", "''");
    }

    /** A cached price with its fetch time. An empty Optional caches a known miss. */
    private record CachedPrice(Optional<Double> price, Instant fetchedAt) {
        boolean isExpired() {
            return Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) > 0;
        }
    }
}
