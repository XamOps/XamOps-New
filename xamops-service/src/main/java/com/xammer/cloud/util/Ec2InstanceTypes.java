package com.xammer.cloud.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for reasoning about EC2 instance type names.
 *
 * <p>Used to decide whether a rightsizing recommendation keeps the same CPU
 * architecture. An x86 -&gt; arm64 change requires an AMI built for the target
 * architecture, so it cannot be applied with a stop / modify-instance-type / start
 * sequence — the instance would fail to boot.
 */
public final class Ec2InstanceTypes {

    public static final String ARCH_ARM64 = "arm64";
    public static final String ARCH_X86_64 = "x86_64";
    public static final String ARCH_UNKNOWN = "unknown";

    /**
     * Matches the family prefix and generation digits of an instance type, e.g. the
     * {@code m6} and {@code g} of {@code m6g.xlarge}. Group 1 is the letter prefix,
     * group 2 the generation number, group 3 any suffix letters.
     */
    private static final Pattern TYPE_PATTERN =
            Pattern.compile("^([a-z]+)(\\d+)([a-z]*)\\..+$", Pattern.CASE_INSENSITIVE);

    /**
     * Instance-type families whose names carry no generation digit but are Graviton
     * based. Kept explicit because the suffix heuristic cannot classify them.
     */
    private static final Set<String> ARM_ONLY_FAMILIES = Set.of("a1");

    private Ec2InstanceTypes() {
    }

    /**
     * Returns the CPU architecture implied by an instance type name.
     *
     * <p>AWS encodes Graviton with a {@code g} in the suffix letters after the
     * generation digit — {@code m6g}, {@code c7g}, {@code t4g}, {@code r8g},
     * {@code m7gd}, {@code c6gn}. The {@code g} must be in the suffix: {@code g4dn} and
     * {@code g5} are x86 GPU families where {@code g} is the family prefix, and
     * {@code inf1}/{@code trn1} are likewise x86.
     *
     * @param instanceType instance type such as {@code m6g.xlarge}; may be null
     * @return {@link #ARCH_ARM64}, {@link #ARCH_X86_64}, or {@link #ARCH_UNKNOWN} when
     *         the name cannot be parsed
     */
    public static String architectureOf(String instanceType) {
        if (instanceType == null || instanceType.isBlank()) {
            return ARCH_UNKNOWN;
        }
        String type = instanceType.trim().toLowerCase();

        String family = type.contains(".") ? type.substring(0, type.indexOf('.')) : type;
        if (ARM_ONLY_FAMILIES.contains(family)) {
            return ARCH_ARM64;
        }

        Matcher m = TYPE_PATTERN.matcher(type);
        if (!m.matches()) {
            return ARCH_UNKNOWN;
        }
        // Suffix letters that follow the generation digit, e.g. "gd" in m7gd.large.
        String suffix = m.group(3);
        return suffix.contains("g") ? ARCH_ARM64 : ARCH_X86_64;
    }

    /**
     * Returns true when moving between these two instance types changes CPU
     * architecture. Returns false when either architecture is unknown, so that an
     * unparseable name does not block an otherwise valid resize; callers that need a
     * hard guarantee should verify against the instance's actual AMI.
     */
    public static boolean isArchitectureChange(String fromType, String toType) {
        String from = architectureOf(fromType);
        String to = architectureOf(toType);
        if (ARCH_UNKNOWN.equals(from) || ARCH_UNKNOWN.equals(to)) {
            return false;
        }
        return !from.equals(to);
    }
}
