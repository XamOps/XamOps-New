package com.xammer.cloud.service.autospotting.aws;

/**
 * What one launch template version and its AMI say about a group's instances (design-aws.md §4.2,
 * §7.2): read once per distinct template version and AMI, cached 24 h, because both eligibility and
 * pricing need it. Values only; no cloud access.
 *
 * @param resolvedVersion      the numeric version the spec resolved to (context only, never written back)
 * @param versionSha256        hash of the version's relevant content (context only, FR-SEC-06: not copied)
 * @param marketOptionsSet     the template sets {@code InstanceMarketOptions} (blocks a mixed policy)
 * @param marketType           the market type when set (for example {@code spot}), else null
 * @param tenancy              placement tenancy ({@code default}, {@code dedicated}, {@code host}) or null
 * @param capacityReservationPreference e.g. {@code open}, {@code none}, {@code capacity-reservations-only}, or null
 * @param platformDetails      the AMI's {@code PlatformDetails}, e.g. {@code Linux/UNIX}
 */
public record AwsLaunchInfo(
        Integer resolvedVersion,
        String versionSha256,
        String instanceType,
        String imageId,
        boolean marketOptionsSet,
        String marketType,
        String tenancy,
        String hostResourceGroupArn,
        String placementGroupName,
        String capacityReservationPreference,
        boolean capacityReservationTargeted,
        String instanceProfileArn,
        String instanceProfileName,
        int ephemeralMappings,
        boolean efaRequested,
        int networkInterfaces,
        boolean hibernation,
        String architecture,
        String bootMode,
        boolean enaSupport,
        String platformDetails,
        String usageOperation) {

    /** True when the template pins Dedicated Hosts or a host resource group, or dedicated tenancy. */
    public boolean tenancyUnsupported() {
        return "host".equalsIgnoreCase(tenancy) || "dedicated".equalsIgnoreCase(tenancy)
                || (hostResourceGroupArn != null && !hostResourceGroupArn.isBlank());
    }

    /** True when the template can only run in On-Demand Capacity Reservations. */
    public boolean capacityReservationOnly() {
        return "capacity-reservations-only".equalsIgnoreCase(capacityReservationPreference)
                || capacityReservationTargeted;
    }

    /** True for a Linux/UNIX AMI; anything else is flagged {@code NON_LINUX}. */
    public boolean linux() {
        return platformDetails == null || platformDetails.isBlank() || platformDetails.startsWith("Linux/UNIX");
    }

    /** True when the template's reservation preference is {@code open} (the EC2 default when unset). */
    public boolean reservationPreferenceOpen() {
        return capacityReservationPreference == null || "open".equalsIgnoreCase(capacityReservationPreference);
    }
}
