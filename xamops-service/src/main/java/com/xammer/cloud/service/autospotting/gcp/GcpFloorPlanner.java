package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The On-Demand floor, Option A (design-gcp.md §5.2, PO-19): the two-version MIG. {@code xas-standard} runs the
 * customer's original template, unchanged, with {@code targetSize.fixed = N}; {@code xas-spot} runs the Spot copy
 * and takes the remainder. Full Spot (N = 0) is one Spot version. The native {@code provisioningModelMix} is never
 * used ({@code autospotting.gcp.native-mix-enabled} stays false).
 */
public final class GcpFloorPlanner {
    private static final ObjectMapper JSON = new ObjectMapper();

    private GcpFloorPlanner() {
    }

    /** The {@code versions[]} of a conversion PATCH. */
    public static ArrayNode versions(int floorN, String originalTemplate, String spotTemplate) {
        ArrayNode versions = JSON.createArrayNode();
        if (floorN > 0) {
            ObjectNode std = versions.addObject();
            std.put("name", GcpMigFacts.STANDARD_VERSION);
            std.put("instanceTemplate", originalTemplate);
            std.putObject("targetSize").put("fixed", floorN);
        }
        ObjectNode spot = versions.addObject();
        spot.put("name", GcpMigFacts.SPOT_VERSION);
        spot.put("instanceTemplate", spotTemplate);
        return versions;
    }

    /** Version name to the template its instances must run. */
    public static Map<String, String> expected(int floorN, String originalTemplate, String spotTemplate) {
        Map<String, String> m = new LinkedHashMap<>();
        if (floorN > 0) {
            m.put(GcpMigFacts.STANDARD_VERSION, originalTemplate);
        }
        m.put(GcpMigFacts.SPOT_VERSION, spotTemplate);
        return m;
    }

    /** At least this many instances must run the standard version once the rollout is done (§4.4 condition 4). */
    public static int minStandard(int floorN, int targetSize) {
        return Math.min(Math.max(0, floorN), Math.max(0, targetSize));
    }
}
