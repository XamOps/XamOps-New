package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The AWS owned-field fingerprint (design-aws.md §5.2): SHA-256 over canonical JSON of the launch
 * template id or name (not the version string), the override list sorted by type, the six
 * {@code InstancesDistribution} fields and {@code CapacityRebalance}.
 *
 * <p>The launch template version string is deliberately excluded (FR-AWS-06, FR-REV-02): an
 * EBS-driven version change is MINOR context drift, never DR-2. Nothing outside the managed fields
 * moves the hash, so min/max/desired changes by a scaling policy never read as drift.
 */
public final class AwsFingerprint {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private AwsFingerprint() {
    }

    /** The fingerprint of a group's owned fields. */
    public static String of(AwsGroupFacts g) {
        return of(g, false);
    }

    /**
     * The fingerprint of a group's owned fields. The instance maintenance policy is part of it only when
     * XamOps owns it (design-aws.md AD-08, D-23-09): a customer's own policy is context, so editing it is
     * never DR-2, while an edit of a policy XamOps set is.
     */
    public static String of(AwsGroupFacts g, boolean ownsMaintenancePolicy) {
        return sha256(canonicalJson(g, ownsMaintenancePolicy));
    }

    /** The canonical JSON the fingerprint hashes (exposed for tests and the snapshot's {@code owned} block). */
    public static String canonicalJson(AwsGroupFacts g) {
        return canonicalJson(g, false);
    }

    /** The canonical JSON, with the maintenance policy only when {@code ownsMaintenancePolicy}. */
    public static String canonicalJson(AwsGroupFacts g, boolean ownsMaintenancePolicy) {
        Map<String, Object> m = new TreeMap<>();
        m.put("launchTemplate", g.launchTemplate() == null ? null : g.launchTemplate().idOrName());
        List<String> types = new ArrayList<>(g.overrideTypes());
        types.sort(String::compareTo);
        m.put("overrides", types);
        Map<String, Object> dist = new TreeMap<>();
        dist.put("onDemandAllocationStrategy", g.onDemandAllocationStrategy());
        dist.put("onDemandBaseCapacity", g.onDemandBase());
        dist.put("onDemandPercentageAboveBaseCapacity", g.onDemandPercentageAboveBase());
        dist.put("spotAllocationStrategy", g.spotAllocationStrategy());
        dist.put("spotInstancePools", g.spotInstancePools());
        dist.put("spotMaxPrice", g.spotMaxPrice());
        m.put("instancesDistribution", g.mixedInstancesPolicy() ? dist : null);
        m.put("capacityRebalance", g.capacityRebalance());
        if (ownsMaintenancePolicy) {
            Map<String, Object> policy = new TreeMap<>();
            policy.put("min", g.minHealthyPercentage());
            policy.put("max", g.maxHealthyPercentage());
            m.put("instanceMaintenancePolicy", policy);
        }
        try {
            return CANONICAL.writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("fingerprint serialization failed", e);
        }
    }

    /** Lower-case hex SHA-256 of {@code text}. */
    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
