package com.xammer.cloud.service.gcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class GcpPricingService {
    private static final Logger logger = LoggerFactory.getLogger(GcpPricingService.class);

    // Estimated On-Demand pricing for us-central1 (USD/Hour)
    private static final Map<String, Double> PRICING_MAP = new HashMap<>();

    static {
        // E2 Standard (General Purpose)
        PRICING_MAP.put("e2-micro", 0.008);
        PRICING_MAP.put("e2-small", 0.016);
        PRICING_MAP.put("e2-medium", 0.033);
        PRICING_MAP.put("e2-standard-2", 0.067);
        PRICING_MAP.put("e2-standard-4", 0.134);
        PRICING_MAP.put("e2-standard-8", 0.268);

        // N1 Standard (General Purpose - Older Gen)
        PRICING_MAP.put("n1-standard-1", 0.0475);
        PRICING_MAP.put("n1-standard-2", 0.0950);
        PRICING_MAP.put("n1-standard-4", 0.1900);
        PRICING_MAP.put("n1-standard-8", 0.3800);

        // N2 Standard (General Purpose - Newer Gen)
        PRICING_MAP.put("n2-standard-2", 0.097);
        PRICING_MAP.put("n2-standard-4", 0.194);
        PRICING_MAP.put("n2-standard-8", 0.388);

        // C2 (Compute Optimized)
        PRICING_MAP.put("c2-standard-4", 0.208);
        PRICING_MAP.put("c2-standard-8", 0.416);
    }

    public double getHourlyCost(String machineType, String region) {
        if (machineType == null)
            return 0.0;

        // Strip URL if present (e.g., "zones/us-central1-a/machineTypes/e2-medium")
        String typeName = machineType;
        if (machineType.contains("/")) {
            typeName = machineType.substring(machineType.lastIndexOf("/") + 1);
        }

        // Simple fallback estimation if type not found
        return PRICING_MAP.getOrDefault(typeName, 0.05);
    }

    public double calculateMonthlySavings(String machineType, String region, int stoppedHoursPerMonth) {
        double hourlyCost = getHourlyCost(machineType, region);
        double savings = hourlyCost * stoppedHoursPerMonth;
        return Math.round(savings * 100.0) / 100.0;
    }
}