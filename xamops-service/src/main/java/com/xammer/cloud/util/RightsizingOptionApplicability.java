package com.xammer.cloud.util;

/**
 * Decides whether a rightsizing recommendation column can be applied in place.
 *
 * <p>The curated rightsizing matrix produces one text cell per CPU vendor, e.g.
 * {@code "t3.medium ($12.04/mo)"}, {@code "Stay t3.large"} or
 * {@code "Not available in ap-south-2"}. Only the first of those is a resize, but the UI
 * previously offered an Apply button for all three, and parsing the second and third for
 * their leading token submitted {@code "Stay"} and {@code "Not"} as instance types.
 *
 * <p>Separately, the matrix pairs some Graviton sources with x86 targets, e.g.
 * {@code t4g.small -> t3.nano}. That saving is real but needs an AMI built for the target
 * architecture, so a stop / modify-instance-type / start sequence would leave the instance
 * unable to boot. Such options are reported as blocked and flagged distinctly, so the
 * recommendation can still be shown without offering a one-click action that must fail.
 *
 * <p>Pure and dependency-free so it can be exercised directly.
 */
public final class RightsizingOptionApplicability {

    /** Matches a bare EC2 instance type such as {@code m6i.large}. */
    private static final String INSTANCE_TYPE_REGEX = "^[a-z0-9]+\\.[a-z0-9]+$";

    private RightsizingOptionApplicability() {
    }

    /**
     * The verdict for one recommendation column.
     *
     * @param applicable         true when the column is a resize that can be applied in place
     * @param reason             why it cannot be applied; null when {@code applicable} is true
     * @param architectureChange true when the block is specifically an x86/arm64 crossing
     * @param targetType         the parsed target instance type, or null when there is none
     */
    public record Verdict(boolean applicable, String reason, boolean architectureChange, String targetType) {

        static Verdict ok(String targetType) {
            return new Verdict(true, null, false, targetType);
        }

        static Verdict blocked(String reason) {
            return new Verdict(false, reason, false, null);
        }

        static Verdict blocked(String reason, String targetType) {
            return new Verdict(false, reason, false, targetType);
        }

        static Verdict architectureBlocked(String reason, String targetType) {
            return new Verdict(false, reason, true, targetType);
        }
    }

    /**
     * Judges one recommendation column against the instance's current type.
     *
     * @param optionText         the column text; may be null or blank
     * @param currentInstanceType the instance's current type, e.g. {@code t4g.small}
     */
    public static Verdict evaluate(String optionText, String currentInstanceType) {
        if (optionText == null || optionText.isBlank()) {
            return Verdict.blocked("No recommendation for this architecture");
        }

        String lower = optionText.trim().toLowerCase();
        if (lower.startsWith("stay")) {
            return Verdict.blocked("Already correctly sized — no change recommended");
        }
        if (lower.startsWith("not available")) {
            return Verdict.blocked(optionText.trim());
        }
        if (lower.contains("price unavailable")) {
            return Verdict.blocked("Price could not be resolved for this region");
        }

        String targetType = extractInstanceType(optionText);
        if (targetType == null || !targetType.matches(INSTANCE_TYPE_REGEX)) {
            return Verdict.blocked("Recommended type could not be parsed: " + optionText.trim());
        }
        if (targetType.equalsIgnoreCase(currentInstanceType)) {
            return Verdict.blocked("Recommended type matches the current type", targetType);
        }
        if (Ec2InstanceTypes.isArchitectureChange(currentInstanceType, targetType)) {
            return Verdict.architectureBlocked(String.format(
                    "%s to %s changes CPU architecture (%s to %s) — needs an AMI built for the "
                            + "target architecture, so it cannot be applied in place",
                    currentInstanceType, targetType,
                    Ec2InstanceTypes.architectureOf(currentInstanceType),
                    Ec2InstanceTypes.architectureOf(targetType)), targetType);
        }
        return Verdict.ok(targetType);
    }

    /**
     * Extracts {@code t3.medium} from text like {@code "t3.medium ($12.04/mo)"}.
     *
     * @return the leading token, lower-cased, or null when the input is empty
     */
    public static String extractInstanceType(String optionText) {
        if (optionText == null || optionText.isBlank()) {
            return null;
        }
        String[] parts = optionText.trim().split("[\\s,(]+");
        return parts.length > 0 && !parts[0].isBlank() ? parts[0].trim().toLowerCase() : null;
    }
}
