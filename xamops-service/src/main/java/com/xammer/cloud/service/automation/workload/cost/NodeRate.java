package com.xammer.cloud.service.automation.workload.cost;

/**
 * One node's resolved {@code $/hr}, or an honest reason it has none.
 *
 * <p><strong>Never zero-as-real.</strong> A failed lookup is an {@link #priceable}{@code == false}
 * rate carrying a machine {@link Reason}, not a {@code 0.0} price — the §3.5 fail-honest rule. A real
 * computed zero is not expressible here because a priced rate comes only from a live AWS figure.
 *
 * @param usdPerHour     the live rate, meaningful only when {@link #priceable} is {@code true}
 * @param source         where the rate came from, or {@code null} when unpriceable
 * @param note           human-readable provenance, suitable for a UI tooltip
 * @param instanceType   the EC2 instance type the node reported, or {@code null}
 * @param capacityType   {@code on-demand} / {@code spot} used for pricing
 * @param zone           the node's availability zone, or {@code null}
 * @param region         the cluster region the on-demand price was taken in
 * @param priceable      whether {@link #usdPerHour} is a real figure
 * @param reason         the machine reason when unpriceable, or {@code null} when priceable
 * @param capacityTypeInferred {@code true} when no capacity-type label was present and on-demand was
 *                             assumed — priced but NOT authoritative (§3.5)
 */
public record NodeRate(double usdPerHour, Source source, String note, String instanceType,
                       String capacityType, String zone, String region, boolean priceable,
                       Reason reason, boolean capacityTypeInferred) {

    /** Where a priced rate came from. */
    public enum Source {
        /** Live AWS Price List on-demand rate. */
        PRICE_LIST,
        /** EC2 DescribeSpotPriceHistory, AZ-filtered. */
        SPOT_HISTORY
    }

    /** The machine reason a node could not be priced. */
    public enum Reason {
        UNKNOWN_INSTANCE_TYPE,
        NO_LIVE_PRICE,
        NO_SPOT_HISTORY,
        NODE_ZONE_UNKNOWN,
        FARGATE_UNPRICEABLE,
        NODE_CAPACITY_UNKNOWN,
        PRICING_DISABLED
    }

    /** A real priced rate. */
    public static NodeRate priceable(double usdPerHour, Source source, String note, String instanceType,
                                     String capacityType, String zone, String region) {
        return new NodeRate(usdPerHour, source, note, instanceType, capacityType, zone, region, true,
                null, false);
    }

    /** A real priced rate for a node whose capacity-type label was absent (on-demand assumed). */
    public static NodeRate inferred(double usdPerHour, Source source, String note, String instanceType,
                                    String zone, String region) {
        return new NodeRate(usdPerHour, source, note, instanceType, "on-demand", zone, region, true,
                null, true);
    }

    /** An unpriceable node: a reason, never a number. */
    public static NodeRate unpriceable(Reason reason, String note, String instanceType,
                                       String capacityType, String zone, String region) {
        return new NodeRate(0.0, null, note, instanceType, capacityType, zone, region, false, reason,
                false);
    }

    /** True for the structural verdicts held the full success TTL; transient verdicts recover sooner. */
    public boolean isStructural() {
        return reason == Reason.UNKNOWN_INSTANCE_TYPE
                || reason == Reason.FARGATE_UNPRICEABLE
                || reason == Reason.NODE_CAPACITY_UNKNOWN
                || reason == Reason.NODE_ZONE_UNKNOWN;
    }
}
