package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The instance maintenance policy rule (design-aws.md AD-08, D-23-09). A pure function over the live
 * {@code MinHealthyPercentage} / {@code MaxHealthyPercentage}, used by the eligibility rules, the plan target,
 * the request builder and the fingerprint, so they cannot disagree.
 *
 * <p>A group with no policy gets 100/200 as an XamOps-owned field (snapshotted including "absent", restored
 * exactly at revert). A customer's own policy is never overwritten. Absent is also what AWS reports as -1/-1,
 * and -1/-1 is how an update clears a policy.
 */
public final class AwsMaintenancePolicy {

    public static final int OWNED_MIN = 100;
    public static final int OWNED_MAX = 200;
    /** The value an update sends to clear a policy. */
    public static final int CLEAR = -1;
    public static final String KEY = "instanceMaintenancePolicy";

    private static final ObjectMapper JSON = new ObjectMapper();

    private AwsMaintenancePolicy() {
    }

    /** How the live policy relates to what XamOps does. */
    public enum Kind {
        /** No policy: XamOps owns 100/200. */
        ABSENT,
        /** {@code Max = 100}: replacements terminate first. Blocked. */
        TERMINATE_FIRST,
        /** {@code Min = 100}: the customer already keeps full capacity. Left as is. */
        CUSTOMER_SAFE,
        /** {@code Min < 100}: the customer allows a dip. Left as is, with a warning. */
        CUSTOMER_LOW_MIN
    }

    public static Kind classify(Integer min, Integer max) {
        Integer mn = normalize(min);
        Integer mx = normalize(max);
        if (mn == null && mx == null) {
            return Kind.ABSENT;
        }
        if (mx != null && mx == 100) {
            return Kind.TERMINATE_FIRST;
        }
        if (mn != null && mn < 100) {
            return Kind.CUSTOMER_LOW_MIN;
        }
        return Kind.CUSTOMER_SAFE;
    }

    public static Kind classify(AwsGroupFacts g) {
        return classify(g.minHealthyPercentage(), g.maxHealthyPercentage());
    }

    /** A value below zero (AWS's -1) means "not set". */
    public static Integer normalize(Integer v) {
        return v == null || v < 0 ? null : v;
    }

    /** {@code {"owned":true,"min":100,"max":200}}: what a plan target carries when XamOps owns the policy. */
    public static ObjectNode ownedNode() {
        ObjectNode n = JSON.createObjectNode();
        n.put("owned", true);
        n.put("min", OWNED_MIN);
        n.put("max", OWNED_MAX);
        return n;
    }

    /** True when a target or desired configuration says XamOps owns the policy. */
    public static boolean owns(JsonNode config) {
        return config != null && config.path(KEY).path("owned").asBoolean(false);
    }

    /** Same as {@link #owns(JsonNode)} for a stored JSON string (desired_config); false when unreadable. */
    public static boolean owns(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return false;
        }
        try {
            return owns(JSON.readTree(configJson));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    /** True when the configuration carries a policy to write (min and max both present). */
    public static boolean hasValue(JsonNode config) {
        JsonNode p = config == null ? null : config.path(KEY);
        return p != null && p.isObject() && p.hasNonNull("min") && p.hasNonNull("max");
    }
}
