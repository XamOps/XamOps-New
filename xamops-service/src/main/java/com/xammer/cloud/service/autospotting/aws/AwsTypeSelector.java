package com.xammer.cloud.service.autospotting.aws;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Instance-type diversification for an ASG (design-aws.md §4). {@code price-capacity-optimized} only
 * works well with many compatible pools (§4.1), so the selector hard-filters candidate types against
 * the original (§4.3) and ranks the survivors (§4.4), targeting {@code target-count} types.
 *
 * <p>All inputs are read-only facts about the original type and candidate types (from
 * {@code DescribeInstanceTypes}, {@code DescribeImages} and {@code DescribeInstanceTypeOfferings});
 * this class does no I/O, so it is unit-testable with fixtures per rule (AC-AWS-03).
 */
public final class AwsTypeSelector {

    /** The API cap on overrides (§4.4, AC-AWS-03). */
    public static final int MAX_TYPES = 40;
    /** Fewer than this is {@code FEW_TYPES} warning (§4.4). */
    public static final int FEW_TYPES_THRESHOLD = 4;
    /** Fewer than this (the original alone) is {@code NO_COMPATIBLE_TYPES} (§4.4). */
    public static final int MIN_TYPES = 2;

    private final int targetCount;
    private final boolean allowBurstable;

    public AwsTypeSelector(int targetCount) {
        this(targetCount, false);
    }

    /**
     * @param allowBurstable the customer's per-plan choice (design-aws.md AD-09, F-23-04): a burstable original may
     *                       gain burstable alternatives that pass every other rule. Off by default.
     */
    public AwsTypeSelector(int targetCount, boolean allowBurstable) {
        this.targetCount = Math.min(Math.max(targetCount, MIN_TYPES), MAX_TYPES);
        this.allowBurstable = allowBurstable;
    }

    /** The same selector with the burstable option set. */
    public AwsTypeSelector withBurstable(boolean allow) {
        return allow == allowBurstable ? this : new AwsTypeSelector(targetCount, allow);
    }

    public boolean allowsBurstable() {
        return allowBurstable;
    }

    /** The AMI facts a candidate must be compatible with (§4.3). */
    public record ImageFacts(String architecture, String bootMode, boolean enaSupport) {
    }

    /** The launch-template facts that gate candidates (§4.3 rules 9, 11, 12). */
    public record TemplateFacts(int ephemeralMappings, boolean efaRequested, int networkInterfaces,
                                boolean hibernationEnabled) {
        public static TemplateFacts none() {
            return new TemplateFacts(0, false, 0, false);
        }
    }

    /** A type's attributes from {@code DescribeInstanceTypes} (§4.2), plus the AZs it is offered in. */
    public record TypeFacts(String instanceType,
                            int vcpu,
                            long memoryMib,
                            Set<String> architectures,
                            Set<String> supportedUsageClasses,
                            boolean burstable,
                            boolean bareMetal,
                            boolean nitro,
                            Set<String> supportedBootModes,
                            int acceleratorCount,
                            String acceleratorModel,
                            int instanceStoreDisks,
                            boolean efaSupported,
                            int maxNetworkInterfaces,
                            boolean hibernationSupported,
                            boolean currentGeneration,
                            Set<String> offeredAzs,
                            double medianSpotPrice) {
    }

    /** The outcome of selection: the chosen overrides (original first), and the counts for the warnings. */
    public record Selection(List<String> types, int compatibleCount) {
        public boolean noCompatibleTypes() {
            return compatibleCount < MIN_TYPES;
        }

        public boolean fewTypes() {
            return compatibleCount >= MIN_TYPES && compatibleCount < FEW_TYPES_THRESHOLD;
        }
    }

    /**
     * Filter and rank candidates against the original type. The original is always included first
     * (the On-Demand priority type, §4.4); candidates are the compatible survivors ranked by tier and
     * then by median Spot price, capped at {@link #MAX_TYPES} and the target count.
     *
     * @param original   the group's current type facts (also the On-Demand floor type)
     * @param groupAzs   the group's AZs
     * @param image      the AMI facts the template resolves to
     * @param template   the launch-template facts
     * @param candidates the candidate type facts (excluding the original)
     */
    public Selection select(TypeFacts original, Set<String> groupAzs, ImageFacts image,
                            TemplateFacts template, List<TypeFacts> candidates) {
        Objects.requireNonNull(original, "original");
        List<TypeFacts> compatible = new ArrayList<>();
        // The original always qualifies (it's the customer's own type and the On-Demand floor).
        compatible.add(original);
        for (TypeFacts c : candidates) {
            if (c.instanceType().equals(original.instanceType())) {
                continue;
            }
            if (isCompatible(original, c, groupAzs, image, template)) {
                compatible.add(c);
            }
        }
        // Rank: original first, then by tier, then ascending median Spot price (§4.4).
        List<TypeFacts> ranked = new ArrayList<>(compatible);
        ranked.sort(Comparator
                .comparingInt((TypeFacts t) -> tier(original, t))
                .thenComparingDouble(TypeFacts::medianSpotPrice)
                .thenComparing(TypeFacts::instanceType));
        List<String> result = new ArrayList<>();
        for (TypeFacts t : ranked) {
            if (result.size() >= Math.min(targetCount, MAX_TYPES)) {
                break;
            }
            result.add(t.instanceType());
        }
        // Original must always be present and first.
        result.remove(original.instanceType());
        result.add(0, original.instanceType());
        return new Selection(result, compatible.size());
    }

    /**
     * Whether a type with {@code supported} boot modes ({@code legacy-bios}, {@code uefi}) can boot an AMI
     * whose DescribeImages {@code BootMode} is {@code imageBootMode}. {@code uefi-preferred} (the default of
     * every AL2023 AMI) boots UEFI where the type supports it and legacy BIOS otherwise, so either supported
     * mode is enough. Before phase 23 defect D-23-05 this was a plain {@code contains}, and
     * {@code uefi-preferred} matched no type. An unset or blank mode is resolved by the caller to the
     * architecture default ({@link #defaultBootMode}); here it is treated as flexible only as a fallback.
     */
    /** AWS default boot mode of an AMI that sets none: arm64 boots uefi, everything else legacy-bios. */
    static String defaultBootMode(String architecture) {
        return "arm64".equals(architecture) ? "uefi" : "legacy-bios";
    }

    static boolean bootsImage(Set<String> supported, String imageBootMode) {
        if (imageBootMode == null || imageBootMode.isBlank() || "uefi-preferred".equals(imageBootMode)) {
            return supported.contains("uefi") || supported.contains("legacy-bios");
        }
        return supported.contains(imageBootMode);
    }

    /** True when candidate C is compatible with original O under every §4.3 rule. */
    boolean isCompatible(TypeFacts o, TypeFacts c, Set<String> groupAzs, ImageFacts image, TemplateFacts template) {
        // 1. Architecture: candidate must support the AMI's architecture. Mac excluded.
        if (!c.architectures().contains(image.architecture())) {
            return false;
        }
        if (c.instanceType().startsWith("mac")) {
            return false;
        }
        // 2. Size: equal vCPU; O.memory <= C.memory <= 2 * O.memory.
        if (c.vcpu() != o.vcpu()) {
            return false;
        }
        if (c.memoryMib() < o.memoryMib() || c.memoryMib() > 2L * o.memoryMib()) {
            return false;
        }
        // 3. Spot supported.
        if (!c.supportedUsageClasses().contains("spot")) {
            return false;
        }
        // 4. Offered: in >= 2 of the group's AZs, or every AZ for a single-AZ group.
        long offeredInGroup = groupAzs.stream().filter(c.offeredAzs()::contains).count();
        if (groupAzs.size() <= 1) {
            if (offeredInGroup < groupAzs.size()) {
                return false;
            }
        } else if (offeredInGroup < 2) {
            return false;
        }
        // 5. Burstable (AD-09): never added, unless the customer allowed it and the original is burstable itself.
        // Then a burstable candidate still has to pass every other rule (t3 gains t3a, not t2 or t4g).
        if (c.burstable() && !(allowBurstable && o.burstable())) {
            return false;
        }
        // 6. Hypervisor/drivers: if O is Nitro, C must be Nitro; a Xen original allows Nitro only with ENA.
        if (o.nitro() && !c.nitro()) {
            return false;
        }
        if (!o.nitro() && c.nitro() && !image.enaSupport()) {
            return false;
        }
        // 7. Boot mode: candidate must be able to boot the AMI.
        String imageBoot = image.bootMode() == null || image.bootMode().isBlank()
                ? defaultBootMode(image.architecture()) : image.bootMode();
        if (!bootsImage(c.supportedBootModes(), imageBoot)) {
            return false;
        }
        // 8. Accelerators: same count and model (both none, or both equal).
        if (o.acceleratorCount() != c.acceleratorCount()
                || !Objects.equals(nullToEmpty(o.acceleratorModel()), nullToEmpty(c.acceleratorModel()))) {
            return false;
        }
        // 9. Instance store: C must have at least as many instance-store disks as the template maps.
        if (template.ephemeralMappings() > 0 && c.instanceStoreDisks() < template.ephemeralMappings()) {
            return false;
        }
        // 10. Bare metal: excluded unless O is bare metal.
        if (c.bareMetal() && !o.bareMetal()) {
            return false;
        }
        // 11. Network features: EFA, and enough network interfaces.
        if (template.efaRequested() && !c.efaSupported()) {
            return false;
        }
        if (template.networkInterfaces() > c.maxNetworkInterfaces()) {
            return false;
        }
        // 12. Hibernation.
        if (template.hibernationEnabled() && !c.hibernationSupported()) {
            return false;
        }
        // 13. Previous generation: excluded unless O is previous generation.
        if (!c.currentGeneration() && o.currentGeneration()) {
            return false;
        }
        return true;
    }

    /**
     * The ranking tier of a candidate relative to the original (§4.4): same family other generations
     * first, then sibling processors, then larger-memory families. The original itself is tier 0.
     */
    private static int tier(TypeFacts o, TypeFacts c) {
        if (c.instanceType().equals(o.instanceType())) {
            return 0;
        }
        String oFamily = family(o.instanceType());
        String cFamily = family(c.instanceType());
        if (oFamily.equals(cFamily)) {
            return 1;
        }
        // Sibling processor families (same leading letter class, e.g. m -> m/c/r variants).
        if (!oFamily.isEmpty() && !cFamily.isEmpty() && oFamily.charAt(0) == cFamily.charAt(0)) {
            return 2;
        }
        // Larger-memory families (r-series for an m original) and everything else.
        return 3;
    }

    /** The family prefix of an instance type (the part before the dot and the size), e.g. {@code m5} from {@code m5.large}. */
    private static String family(String instanceType) {
        int dot = instanceType.indexOf('.');
        return dot > 0 ? instanceType.substring(0, dot) : instanceType;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
