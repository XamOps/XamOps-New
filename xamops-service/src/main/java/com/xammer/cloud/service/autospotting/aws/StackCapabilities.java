package com.xammer.cloud.service.autospotting.aws;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
/**
 * What each version of a customer's read-write stack can do for AutoSpotting (design-aws.md §10.1).
 *
 * <p>A table of capabilities rather than one "latest" comparison, so a later template can add a
 * capability without making every earlier stack look broken. A null or unparseable version is older
 * than every capability. Listing, status, revert and fallback never need a capability: every live
 * stack can call {@code UpdateAutoScalingGroup}, which is all that drain and fallback use.
 *
 * <p>A pre-flight simulate pass on an older stack satisfies {@link #AS_PASSROLE_SCOPED} and
 * {@link #AS_QUOTA_READS} (the customer may have added the permission by hand). It does not satisfy
 * {@link #AS_EVENTS_MULTI_REGION}: the event rules are resources, not permissions.
 */
public enum StackCapabilities {
    /** Event rules in every enabled region, from the AutoSpotting events nested stack. */
    AS_EVENTS_MULTI_REGION("rw-2026.11.1", true, "events from every region", false),
    /** {@code iam:PassRole} limited to EC2 and the listed roles. */
    AS_PASSROLE_SCOPED("rw-2026.10.1", true, "scoped iam:PassRole", true),
    /** {@code iam:SimulatePrincipalPolicy} on the connection role itself. */
    AS_SIMULATE_SELF("rw-2026.10.1", true, "permission self-check", false),
    /** Service Quotas and CloudWatch reads for the Spot vCPU quota check. */
    AS_QUOTA_READS("rw-2026.10.1", true, "Spot quota reads", true);
    private final String minimumVersion;
    private final boolean requiredForConversion;
    private final String label;
    private final boolean satisfiedByPreflight;
    StackCapabilities(String minimumVersion, boolean requiredForConversion, String label, boolean satisfiedByPreflight) {
        this.minimumVersion = minimumVersion;
        this.requiredForConversion = requiredForConversion;
        this.label = label;
        this.satisfiedByPreflight = satisfiedByPreflight;
    }
    public String minimumVersion() {
        return minimumVersion;
    }
    /** Whether a conversion needs it (FR-ONB-02). */
    public boolean requiredForConversion() {
        return requiredForConversion;
    }
    /** Short text for the screen. */
    public String label() {
        return label;
    }
    /** Whether a passing pre-flight simulate of the underlying action counts as having it. */
    public boolean satisfiedByPreflight() {
        return satisfiedByPreflight;
    }
    /** True when a stack at {@code version} carries this capability. */
    public boolean presentAt(String version) {
        return atLeast(version, minimumVersion);
    }
    /**
     * The capabilities a conversion needs that a stack at {@code version} lacks.
     *
     * @param provenByPreflight capabilities whose underlying permission a pre-flight simulate
     *                          confirmed; only those that {@link #satisfiedByPreflight()} are honored
     */
    public static List<StackCapabilities> missingForConversion(String version, Set<StackCapabilities> provenByPreflight) {
        List<StackCapabilities> missing = new ArrayList<>();
        for (StackCapabilities c : values()) {
            if (c.requiredForConversion && !c.presentAt(version)
                    && !(c.satisfiedByPreflight && provenByPreflight.contains(c))) {
                missing.add(c);
            }
        }
        return missing;
    }
    public static List<StackCapabilities> missingForConversion(String version) {
        return missingForConversion(version, EnumSet.noneOf(StackCapabilities.class));
    }
    /** The newest minimum version in the table: the version "Update stack" brings an account to. */
    public static String latestVersion() {
        String latest = values()[0].minimumVersion;
        for (StackCapabilities c : values()) {
            if (atLeast(c.minimumVersion, latest)) {
                latest = c.minimumVersion;
            }
        }
        return latest;
    }
    /** {@code rw-YYYY.MM.N} comparison; a null or unparseable {@code have} is older than everything. */
    public static boolean atLeast(String have, String want) {
        int[] h = parse(have);
        int[] w = parse(want);
        if (h == null || w == null) {
            return false;
        }
        for (int i = 0; i < w.length; i++) {
            if (h[i] != w[i]) {
                return h[i] > w[i];
            }
        }
        return true;
    }
    /** Comma-separated labels, for messages. */
    public static String describe(List<StackCapabilities> capabilities) {
        List<String> labels = new ArrayList<>();
        for (StackCapabilities c : capabilities) {
            labels.add(c.label);
        }
        return String.join(", ", labels);
    }
    private static int[] parse(String v) {
        if (v == null || !v.startsWith("rw-")) {
            return null;
        }
        String[] parts = v.substring(3).split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
