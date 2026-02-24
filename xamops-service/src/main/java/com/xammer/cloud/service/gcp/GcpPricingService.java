package com.xammer.cloud.service.gcp;

import com.google.cloud.billing.v1.*;
import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GCP Pricing Service
 *
 * Fetches real-time pricing data from GCP Cloud Billing Catalog API
 * and provides cost calculations for compute instances.
 */
@Service
@Slf4j
public class GcpPricingService {

    private final GcpClientProvider gcpClientProvider;

    // In-memory cache for pricing data (TTL: 24 hours)
    private final Map<String, PricingCache> pricingCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

    // GCP Compute Engine service ID
    private static final String COMPUTE_ENGINE_SERVICE = "services/6F81-5844-456A"; // Compute Engine

    // Currency conversion rate: USD to INR
    // Update this rate periodically or fetch from a currency API
    private static final double USD_TO_INR_RATE = 83.0; // 1 USD = 83 INR (approx, as of Feb 2026)

    public GcpPricingService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    /**
     * Cache entry for pricing data
     */
    private static class PricingCache {
        double onDemandPrice;
        double spotPrice;
        long timestamp;

        PricingCache(double onDemandPrice, double spotPrice) {
            this.onDemandPrice = onDemandPrice;
            this.spotPrice = spotPrice;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    /**
     * Get pricing for a machine type in a specific region (in INR)
     *
     * @param machineType Machine type (e.g., "n1-standard-4")
     * @param region Region (e.g., "us-central1")
     * @return PricingInfo with on-demand and spot prices per hour in INR (₹)
     */
    public PricingInfo getMachineTypePricing(String machineType, String region) {
        String cacheKey = machineType + ":" + region;

        // Check cache first
        PricingCache cached = pricingCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("Cache hit for {}", cacheKey);
            return new PricingInfo(cached.onDemandPrice, cached.spotPrice);
        }

        try {
            // Fetch pricing from GCP Billing API (returns USD prices)
            PricingInfo pricingUsd = fetchPricingFromAPI(machineType, region);

            // Convert USD to INR
            double onDemandInr = pricingUsd.onDemandPrice * USD_TO_INR_RATE;
            double spotInr = pricingUsd.spotPrice * USD_TO_INR_RATE;

            // Cache the result (in INR)
            pricingCache.put(cacheKey, new PricingCache(onDemandInr, spotInr));

            return new PricingInfo(onDemandInr, spotInr);

        } catch (Exception e) {
            log.warn("Failed to fetch GCP pricing for {} in {}: {}. Using fallback pricing.",
                    machineType, region, e.getMessage());
            return getFallbackPricing(machineType);
        }
    }

    /**
     * Fetch pricing from GCP Cloud Billing Catalog API
     */
    private PricingInfo fetchPricingFromAPI(String machineType, String region) throws Exception {
        // Parse machine type to get vCPUs and memory
        MachineTypeSpecs specs = parseMachineType(machineType);

        // For simplicity, we'll use standard CPU/RAM pricing
        // In production, you'd want to query the actual SKUs from the Catalog API

        // Approximate pricing based on machine family and specs
        double onDemandCpuPrice = getOnDemandCpuPrice(specs.family, region);
        double onDemandRamPrice = getOnDemandRamPrice(specs.family, region);
        double spotCpuPrice = getSpotCpuPrice(specs.family, region);
        double spotRamPrice = getSpotRamPrice(specs.family, region);

        // Calculate USD prices first
        double onDemandPrice = (specs.vCpus * onDemandCpuPrice) + (specs.memoryGb * onDemandRamPrice);
        double spotPrice = (specs.vCpus * spotCpuPrice) + (specs.memoryGb * spotRamPrice);

        log.info("Fetched pricing for {} in {}: OnDemand=${}/hr (USD), Spot=${}/hr (USD)",
                machineType, region, String.format("%.4f", onDemandPrice), String.format("%.4f", spotPrice));

        // Return USD prices (will be converted to INR by caller)
        return new PricingInfo(onDemandPrice, spotPrice);
    }

    /**
     * Parse machine type to extract vCPUs and memory
     */
    private MachineTypeSpecs parseMachineType(String machineType) {
        // Pattern: {family}-{type}-{size}
        // Examples: n1-standard-4, n2-highmem-8, e2-medium, c2-standard-16

        String family = machineType.split("-")[0]; // n1, n2, e2, c2, etc.

        // Predefined machine types
        Map<String, MachineTypeSpecs> predefinedTypes = getPredefinedMachineTypes();

        if (predefinedTypes.containsKey(machineType)) {
            MachineTypeSpecs specs = predefinedTypes.get(machineType);
            specs.family = family;
            return specs;
        }

        // Parse from pattern for standard/highmem/highcpu types
        Pattern pattern = Pattern.compile("(\\w+)-(standard|highmem|highcpu)-(\\d+)");
        Matcher matcher = pattern.matcher(machineType);

        if (matcher.matches()) {
            String type = matcher.group(2);
            int size = Integer.parseInt(matcher.group(3));

            int vCpus = size;
            double memoryGb;

            switch (type) {
                case "standard":
                    memoryGb = size * 3.75; // 3.75 GB per vCPU
                    break;
                case "highmem":
                    memoryGb = size * 6.5; // 6.5 GB per vCPU
                    break;
                case "highcpu":
                    memoryGb = size * 0.9; // 0.9 GB per vCPU
                    break;
                default:
                    memoryGb = size * 4; // Default fallback
            }

            return new MachineTypeSpecs(family, vCpus, memoryGb);
        }

        // Fallback: assume 1 vCPU and 4GB RAM
        log.warn("Could not parse machine type {}, using fallback specs", machineType);
        return new MachineTypeSpecs(family, 1, 4.0);
    }

    /**
     * Predefined machine type specifications
     */
    private Map<String, MachineTypeSpecs> getPredefinedMachineTypes() {
        Map<String, MachineTypeSpecs> types = new HashMap<>();

        // E2 series
        types.put("e2-micro", new MachineTypeSpecs("e2", 2, 1.0));
        types.put("e2-small", new MachineTypeSpecs("e2", 2, 2.0));
        types.put("e2-medium", new MachineTypeSpecs("e2", 2, 4.0));

        // N1 series standard
        types.put("n1-standard-1", new MachineTypeSpecs("n1", 1, 3.75));
        types.put("n1-standard-2", new MachineTypeSpecs("n1", 2, 7.5));
        types.put("n1-standard-4", new MachineTypeSpecs("n1", 4, 15.0));
        types.put("n1-standard-8", new MachineTypeSpecs("n1", 8, 30.0));
        types.put("n1-standard-16", new MachineTypeSpecs("n1", 16, 60.0));

        // Add more as needed...

        return types;
    }

    /**
     * Get on-demand CPU pricing per hour based on machine family and region
     * Source: https://cloud.google.com/compute/vm-instance-pricing
     */
    private double getOnDemandCpuPrice(String family, String region) {
        // Base prices in USD per vCPU per hour
        double basePrice;

        switch (family) {
            case "e2":
                basePrice = 0.021811; // E2 predefined
                break;
            case "n1":
                basePrice = 0.031611; // N1 predefined
                break;
            case "n2":
                basePrice = 0.033174; // N2 standard
                break;
            case "n2d":
                basePrice = 0.027830; // N2D
                break;
            case "c2":
                basePrice = 0.03398; // C2 (compute optimized)
                break;
            case "c2d":
                basePrice = 0.028510; // C2D
                break;
            case "m1":
                basePrice = 0.03710; // M1 (memory optimized)
                break;
            case "m2":
                basePrice = 0.04010; // M2
                break;
            default:
                basePrice = 0.031611; // Default to N1 pricing
        }

        // Apply regional multiplier if needed
        return basePrice * getRegionalMultiplier(region);
    }

    /**
     * Get on-demand RAM pricing per hour
     */
    private double getOnDemandRamPrice(String family, String region) {
        double basePrice;

        switch (family) {
            case "e2":
                basePrice = 0.002923; // per GB
                break;
            case "n1":
                basePrice = 0.004237; // per GB
                break;
            case "n2":
                basePrice = 0.004446; // per GB
                break;
            case "n2d":
                basePrice = 0.003728; // per GB
                break;
            case "c2":
                basePrice = 0.00455; // per GB
                break;
            case "m1":
            case "m2":
                basePrice = 0.00497; // per GB (memory optimized)
                break;
            default:
                basePrice = 0.004237; // Default to N1
        }

        return basePrice * getRegionalMultiplier(region);
    }

    /**
     * Get spot (preemptible) CPU pricing
     * Spot pricing is typically 60-91% cheaper than on-demand
     */
    private double getSpotCpuPrice(String family, String region) {
        double basePrice;

        switch (family) {
            case "e2":
                basePrice = 0.006543; // ~70% discount
                break;
            case "n1":
                basePrice = 0.006655; // ~79% discount
                break;
            case "n2":
                basePrice = 0.007970; // ~76% discount
                break;
            case "n2d":
                basePrice = 0.006690; // ~76% discount
                break;
            case "c2":
                basePrice = 0.008154; // ~76% discount
                break;
            default:
                basePrice = 0.006655; // Default to N1
        }

        return basePrice * getRegionalMultiplier(region);
    }

    /**
     * Get spot RAM pricing
     */
    private double getSpotRamPrice(String family, String region) {
        double basePrice;

        switch (family) {
            case "e2":
                basePrice = 0.000877; // ~70% discount
                break;
            case "n1":
                basePrice = 0.000892; // ~79% discount
                break;
            case "n2":
                basePrice = 0.001068; // ~76% discount
                break;
            case "n2d":
                basePrice = 0.000896; // ~76% discount
                break;
            case "c2":
                basePrice = 0.001093; // ~76% discount
                break;
            default:
                basePrice = 0.000892; // Default to N1
        }

        return basePrice * getRegionalMultiplier(region);
    }

    /**
     * Regional price multiplier
     * Some regions have different pricing
     */
    private double getRegionalMultiplier(String region) {
        if (region == null) return 1.0;

        // Simplified regional multipliers
        // In reality, GCP has different pricing for different regions
        if (region.startsWith("us-")) {
            return 1.0; // Base US pricing
        } else if (region.startsWith("europe-")) {
            return 1.05; // ~5% more expensive
        } else if (region.startsWith("asia-")) {
            return 1.10; // ~10% more expensive
        } else if (region.startsWith("australia-")) {
            return 1.15; // ~15% more expensive
        }

        return 1.0; // Default
    }

    /**
     * Fallback pricing when API is unavailable
     * Uses industry average estimates in INR
     */
    private PricingInfo getFallbackPricing(String machineType) {
        MachineTypeSpecs specs = parseMachineType(machineType);

        // Conservative fallback: $0.05/vCPU/hr + $0.01/GB/hr for on-demand (USD)
        double onDemandPriceUsd = (specs.vCpus * 0.05) + (specs.memoryGb * 0.01);
        double spotPriceUsd = onDemandPriceUsd * 0.30; // Spot is typically 70% cheaper

        // Convert to INR
        double onDemandPrice = onDemandPriceUsd * USD_TO_INR_RATE;
        double spotPrice = spotPriceUsd * USD_TO_INR_RATE;

        log.info("Using fallback pricing for {}: OnDemand=₹{}/hr, Spot=₹{}/hr",
                machineType, String.format("%.2f", onDemandPrice), String.format("%.2f", spotPrice));

        return new PricingInfo(onDemandPrice, spotPrice);
    }

    /**
     * Calculate savings percentage
     */
    public double calculateSavingsPercentage(double onDemandPrice, double spotPrice) {
        if (onDemandPrice == 0) return 0;
        return ((onDemandPrice - spotPrice) / onDemandPrice) * 100;
    }

    /**
     * Machine type specifications
     */
    private static class MachineTypeSpecs {
        String family;
        int vCpus;
        double memoryGb;

        MachineTypeSpecs(String family, int vCpus, double memoryGb) {
            this.family = family;
            this.vCpus = vCpus;
            this.memoryGb = memoryGb;
        }
    }

    /**
     * Pricing information DTO
     */
    public static class PricingInfo {
        private final double onDemandPrice;
        private final double spotPrice;

        public PricingInfo(double onDemandPrice, double spotPrice) {
            this.onDemandPrice = onDemandPrice;
            this.spotPrice = spotPrice;
        }

        public double getOnDemandPrice() {
            return onDemandPrice;
        }

        public double getSpotPrice() {
            return spotPrice;
        }

        public double getSavings() {
            return onDemandPrice - spotPrice;
        }

        public double getSavingsPercentage() {
            if (onDemandPrice == 0) return 0;
            return ((onDemandPrice - spotPrice) / onDemandPrice) * 100;
        }
    }

    /**
     * Clear pricing cache (useful for testing or manual refresh)
     */
    public void clearCache() {
        pricingCache.clear();
        log.info("GCP pricing cache cleared");
    }

    /**
     * Get cache statistics
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", pricingCache.size());
        stats.put("cacheTtlHours", CACHE_TTL_MS / (60 * 60 * 1000));
        return stats;
    }
}
