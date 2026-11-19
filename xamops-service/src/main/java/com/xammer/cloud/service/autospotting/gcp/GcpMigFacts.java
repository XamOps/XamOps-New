package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The facts of one managed instance group read from the resource itself (design-gcp.md §2.2, §6.1).
 * Scope (zonal or regional) comes from the resource's own {@code zone} or {@code region} field and
 * the zone count from {@code distributionPolicy.zones}: neither is guessed (AC-GCP-02).
 */
public final class GcpMigFacts {
    /** The XamOps version names of the two-version MIG (design-gcp.md §5.2). */
    public static final String STANDARD_VERSION = "xas-standard";
    public static final String SPOT_VERSION = "xas-spot";

    /** One entry of {@code versions[]}. */
    public record Version(String name, String instanceTemplate, Integer targetSizeFixed, Integer targetSizePercent) {
    }

    private final String project;
    private final JsonNode raw;
    private final boolean regional;
    private final boolean locationKnown;
    private final String location;

    private GcpMigFacts(String project, JsonNode raw) {
        this.project = project;
        this.raw = raw;
        boolean zone = raw.hasNonNull("zone");
        boolean region = raw.hasNonNull("region");
        this.regional = region && !zone;
        this.locationKnown = zone || region;
        this.location = zone ? last(raw.path("zone").asText()) : region ? last(raw.path("region").asText()) : null;
    }

    public static GcpMigFacts from(String project, JsonNode mig) {
        return new GcpMigFacts(project, mig);
    }

    private static String last(String link) {
        int slash = link.lastIndexOf('/');
        return slash < 0 ? link : link.substring(slash + 1);
    }

    public JsonNode raw() {
        return raw;
    }

    public String project() {
        return project;
    }

    public String name() {
        return raw.path("name").asText(null);
    }

    public String id() {
        return raw.hasNonNull("id") ? raw.path("id").asText() : null;
    }

    public String selfLink() {
        return raw.path("selfLink").asText(null);
    }

    public boolean regional() {
        return regional;
    }

    public boolean locationKnown() {
        return locationKnown;
    }

    /** Zone or region name; null when the resource names neither. */
    public String location() {
        return location;
    }

    public String locationType() {
        return !locationKnown ? "UNKNOWN" : regional ? "REGIONAL" : "ZONAL";
    }

    /** The region: the MIG's region, or the zone's region (zone minus its last label). */
    public String region() {
        if (location == null) {
            return null;
        }
        if (regional) {
            return location;
        }
        int dash = location.lastIndexOf('-');
        return dash < 0 ? location : location.substring(0, dash);
    }

    /** {@code project/location/migName} (design-core.md §9.1). */
    public String groupKey() {
        return project + "/" + (location == null ? "unknown" : location) + "/" + name();
    }

    public List<String> distributionZones() {
        List<String> zones = new ArrayList<>();
        raw.path("distributionPolicy").path("zones").forEach(z -> zones.add(last(z.path("zone").asText())));
        return zones;
    }

    /** The zone count: the regional MIG's distribution zones; 1 for a zonal MIG. */
    public int zoneCount() {
        return regional ? distributionZones().size() : 1;
    }

    /** A zone to read zonal resources (machine types) in: the MIG's zone, or the first distribution zone. */
    public String anyZone() {
        if (!regional) {
            return location;
        }
        List<String> zones = distributionZones();
        return zones.isEmpty() ? null : zones.get(0);
    }

    public String targetShape() {
        return raw.path("distributionPolicy").path("targetShape").asText(null);
    }

    public int targetSize() {
        return raw.path("targetSize").asInt(0);
    }

    public int targetStoppedSize() {
        return raw.path("targetStoppedSize").asInt(0);
    }

    public int targetSuspendedSize() {
        return raw.path("targetSuspendedSize").asInt(0);
    }

    public String instanceTemplate() {
        return raw.path("instanceTemplate").asText(null);
    }

    public List<Version> versions() {
        List<Version> out = new ArrayList<>();
        raw.path("versions").forEach(v -> out.add(new Version(v.path("name").asText(null),
                v.path("instanceTemplate").asText(null),
                v.path("targetSize").hasNonNull("fixed") ? v.path("targetSize").path("fixed").asInt() : null,
                v.path("targetSize").hasNonNull("percent") ? v.path("targetSize").path("percent").asInt() : null)));
        return out;
    }

    /** Every distinct template self-link the MIG references (top-level and per version), in order. */
    public Set<String> templateLinks() {
        Set<String> out = new LinkedHashSet<>();
        if (instanceTemplate() != null) {
            out.add(instanceTemplate());
        }
        versions().forEach(v -> {
            if (v.instanceTemplate() != null) {
                out.add(v.instanceTemplate());
            }
        });
        return out;
    }

    public JsonNode updatePolicy() {
        return raw.path("updatePolicy");
    }

    public JsonNode autoHealingPolicies() {
        return raw.path("autoHealingPolicies");
    }

    public boolean hasAutoHealing() {
        return autoHealingPolicies().isArray() && autoHealingPolicies().size() > 0;
    }

    public JsonNode allInstancesConfig() {
        return raw.path("allInstancesConfig");
    }

    public boolean hasAllInstancesConfig() {
        JsonNode c = allInstancesConfig();
        return c.isObject() && c.size() > 0;
    }

    public JsonNode instanceFlexibilityPolicy() {
        return raw.path("instanceFlexibilityPolicy");
    }

    /** A stateful policy with any preserved state, or a status that says so. */
    public boolean hasStatefulConfig() {
        JsonNode sp = raw.path("statefulPolicy").path("preservedState");
        boolean policy = sp.path("disks").size() > 0 || sp.path("internalIPs").size() > 0
                || sp.path("externalIPs").size() > 0;
        return policy || raw.path("status").path("stateful").path("hasStatefulConfig").asBoolean(false);
    }

    public boolean versionTargetReached() {
        return raw.path("status").path("versionTarget").path("isReached").asBoolean(false);
    }

    public boolean stable() {
        return raw.path("status").path("isStable").asBoolean(false);
    }

    public JsonNode currentActions() {
        return raw.path("currentActions");
    }

    /** The autoscaler link of {@code status.autoscaler}, or null for an unautoscaled MIG. */
    public String autoscalerLink() {
        return raw.path("status").path("autoscaler").asText(null);
    }

    /** The raw MIG etag-like fingerprint: a PATCH precondition only, never a plan staleness check (§2.2). */
    public String rawFingerprint() {
        return raw.path("fingerprint").asText(null);
    }

    /** The customer's {@code mostDisruptiveAllowedAction}, or null when the policy does not set it. */
    public String mostDisruptiveAllowedAction() {
        String v = updatePolicy().path("mostDisruptiveAllowedAction").asText(null);
        return v == null || v.isBlank() ? null : v;
    }

    /** True when the group is the XamOps two-version pair (standard plus spot). */
    public boolean isXamopsPair() {
        List<Version> vs = versions();
        return vs.size() == 2 && vs.stream().anyMatch(v -> STANDARD_VERSION.equals(v.name()))
                && vs.stream().anyMatch(v -> SPOT_VERSION.equals(v.name()));
    }
}
