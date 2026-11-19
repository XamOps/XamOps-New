package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;

import java.util.ArrayList;
import java.util.List;

/**
 * The XamOps rollout policy (design-gcp.md §3.1) and the restore of the customer's policy (§3.4, §5.5
 * PO-GCP-A). Surge-first: a zonal MIG runs {@code maxSurge=1, maxUnavailable=0}; a regional MIG runs
 * {@code maxSurge=<number of zones>, maxUnavailable=0} (Google requires each fixed regional value to be 0 or at
 * least the zone count, and zero unavailable needs a positive surge). The zone count is read from the live MIG's
 * {@code distributionPolicy.zones}; it is never guessed, and a regional MIG with no zones fails with VALIDATION
 * before any mutation. {@code minReadySec} is never sent (V-GCP-NEW-06 is open; v1 has no such field).
 */
public final class GcpRolloutPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();

    private GcpRolloutPolicy() {
    }

    /** @throws GcpReadException VALIDATION when a regional MIG has no distribution zones */
    public static ObjectNode xamopsPolicy(GcpMigFacts g) {
        int surge = surgeFor(g);
        ObjectNode p = JSON.createObjectNode();
        p.put("type", "PROACTIVE");
        p.put("minimalAction", "REPLACE");
        p.put("mostDisruptiveAllowedAction", "REPLACE");
        p.put("replacementMethod", "SUBSTITUTE");
        p.putObject("maxSurge").put("fixed", surge);
        p.putObject("maxUnavailable").put("fixed", 0);
        return p;
    }

    public static int surgeFor(GcpMigFacts g) {
        if (!g.regional()) {
            return 1;
        }
        int zones = g.distributionZones().size();
        if (zones <= 0) {
            throw new GcpReadException("distributionPolicy.zones", ErrorKind.VALIDATION);
        }
        return zones;
    }

    /** The restored policy and the customer fields that were not restored (shown as an acknowledged warning). */
    public record Restored(ObjectNode policy, List<String> overridden) {
    }

    /**
     * The policy to PATCH back after a rollout. N = 0: the customer's policy as it was. N &gt; 0 (PO-GCP-A):
     * every customer field except {@code type} (kept PROACTIVE) and {@code maxUnavailable} (kept 0), with
     * {@code maxSurge} raised to the valid minimum when the customer's value would be invalid with
     * {@code maxUnavailable=0}.
     */
    public static Restored restore(JsonNode customer, int floorN, GcpMigFacts g) {
        ObjectNode p = customer != null && customer.isObject() ? customer.deepCopy() : JSON.createObjectNode();
        List<String> overridden = new ArrayList<>();
        for (String f : new String[] {"maxSurge", "maxUnavailable"}) {
            if (p.path(f).isObject()) {
                ((ObjectNode) p.get(f)).remove("calculated"); // output only
            }
        }
        if (floorN <= 0) {
            return new Restored(p, overridden);
        }
        if (!"PROACTIVE".equals(p.path("type").asText(null))) {
            overridden.add("type");
            p.put("type", "PROACTIVE");
        }
        if (!(p.path("maxUnavailable").path("fixed").isNumber() && p.path("maxUnavailable").path("fixed").asInt() == 0)) {
            overridden.add("maxUnavailable");
            p.putObject("maxUnavailable").put("fixed", 0);
        }
        int min = g.regional() ? Math.max(1, g.distributionZones().size()) : 1;
        JsonNode surge = p.path("maxSurge");
        boolean valid = surge.path("fixed").isNumber() && surge.path("fixed").asInt() >= min
                || surge.path("percent").isNumber() && surge.path("percent").asInt() > 0;
        if (!valid) {
            overridden.add("maxSurge");
            p.putObject("maxSurge").put("fixed", min);
        }
        return new Restored(p, overridden);
    }

    /**
     * The validation rules of a Config save (§3.5): at least one of surge and unavailable above 0; regional
     * fixed values 0 or at least the zone count; both at most the target size. Returns the first violation or null.
     */
    public static String validateEdit(JsonNode policy, boolean regional, int zones, int targetSize) {
        int surge = fixed(policy.path("maxSurge"));
        int unavailable = fixed(policy.path("maxUnavailable"));
        if (surge <= 0 && unavailable <= 0 && policy.has("maxSurge") && policy.has("maxUnavailable")) {
            return "AT_LEAST_ONE_ABOVE_ZERO";
        }
        if (regional) {
            if (policy.path("maxSurge").has("fixed") && surge != 0 && surge < zones) {
                return "REGIONAL_SURGE_BELOW_ZONES";
            }
            if (policy.path("maxUnavailable").has("fixed") && unavailable != 0 && unavailable < zones) {
                return "REGIONAL_UNAVAILABLE_BELOW_ZONES";
            }
        }
        if (surge > targetSize || unavailable > targetSize) {
            return "ABOVE_TARGET_SIZE";
        }
        return null;
    }

    private static int fixed(JsonNode n) {
        return n.path("fixed").asInt(0);
    }
}
