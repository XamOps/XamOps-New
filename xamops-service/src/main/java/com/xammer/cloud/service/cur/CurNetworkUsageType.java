package com.xammer.cloud.service.cur;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the route out of an AWS network usage type.
 *
 * <p>{@code product_from_location} and {@code product_to_location} are the proper answer to
 * "where did this traffic go", and they are not always there: a Data Export is created with
 * a column selection, and a table XamOps did not provision may omit them. The usage type,
 * however, is on every row of every report, and AWS encodes the endpoints into it —
 * {@code APS3-USE1-AWS-Out-Bytes} is Mumbai to N. Virginia, and
 * {@code APS3-DataTransfer-Out-Bytes} is Mumbai to the internet.
 *
 * <p>So this is a fallback, not a replacement. The report's own columns win wherever they
 * exist; this fills the gap, and what it fills is marked as derived so a reader can tell
 * the difference between what AWS said and what was inferred from a naming convention.
 *
 * <p>Short codes are from
 * <a href="https://docs.aws.amazon.com/global-infrastructure/latest/regions/aws-region-billing-codes.html">
 * AWS Region billing codes</a>. A code not in that table is passed through as itself rather
 * than guessed at: "APS9 → USE1" is a worse answer than "Asia Pacific (Thailand) → US East
 * (N. Virginia)" and a far better one than naming the wrong region.
 */
final class CurNetworkUsageType {

    /** What the internet end of an egress or ingress charge is called. */
    static final String INTERNET = "Internet";

    /** What a CloudFront edge is called, for traffic billed to the CDN rather than a region. */
    static final String CLOUDFRONT = "CloudFront edge";

    /**
     * Region short code to the location name AWS itself writes into
     * {@code product_from_location}.
     *
     * <p>Deliberately the long form rather than the region code: a derived route then renders
     * identically to a reported one, so the two merge into the same row instead of appearing
     * as "ap-south-1 → us-east-1" beside "Asia Pacific (Mumbai) → US East (N. Virginia)".
     *
     * <p>{@code EUW1} is not in AWS's published table — the code for eu-west-1 there is
     * {@code EU} — but it does appear in real usage types, so both are mapped.
     */
    private static final Map<String, String> REGIONS = new LinkedHashMap<>();

    static {
        // North America
        REGIONS.put("USE1", "US East (N. Virginia)");
        REGIONS.put("USE2", "US East (Ohio)");
        REGIONS.put("USW1", "US West (N. California)");
        REGIONS.put("USW2", "US West (Oregon)");
        REGIONS.put("CAN1", "Canada (Central)");
        REGIONS.put("CAN2", "Canada West (Calgary)");
        REGIONS.put("MXC1", "Mexico (Central)");
        // Africa
        REGIONS.put("AFS1", "Africa (Cape Town)");
        // Asia Pacific
        REGIONS.put("APE1", "Asia Pacific (Hong Kong)");
        REGIONS.put("APE2", "Asia Pacific (Taipei)");
        REGIONS.put("APN1", "Asia Pacific (Tokyo)");
        REGIONS.put("APN2", "Asia Pacific (Seoul)");
        REGIONS.put("APN3", "Asia Pacific (Osaka)");
        REGIONS.put("APS1", "Asia Pacific (Singapore)");
        REGIONS.put("APS2", "Asia Pacific (Sydney)");
        // Not a typo, and the one everybody gets wrong: APS3 is Mumbai, not Jakarta.
        REGIONS.put("APS3", "Asia Pacific (Mumbai)");
        REGIONS.put("APS4", "Asia Pacific (Jakarta)");
        REGIONS.put("APS5", "Asia Pacific (Hyderabad)");
        REGIONS.put("APS6", "Asia Pacific (Melbourne)");
        REGIONS.put("APS7", "Asia Pacific (Malaysia)");
        REGIONS.put("APS8", "Asia Pacific (New Zealand)");
        REGIONS.put("APS9", "Asia Pacific (Thailand)");
        // Europe
        REGIONS.put("EU", "Europe (Ireland)");
        REGIONS.put("EUW1", "Europe (Ireland)");
        REGIONS.put("EUW2", "Europe (London)");
        REGIONS.put("EUW3", "Europe (Paris)");
        REGIONS.put("EUC1", "Europe (Frankfurt)");
        REGIONS.put("EUC2", "Europe (Zurich)");
        REGIONS.put("EUN1", "Europe (Stockholm)");
        REGIONS.put("EUS1", "Europe (Milan)");
        REGIONS.put("EUS2", "Europe (Spain)");
        // Middle East
        REGIONS.put("ILC1", "Israel (Tel Aviv)");
        REGIONS.put("MEC1", "Middle East (UAE)");
        REGIONS.put("MES1", "Middle East (Bahrain)");
        // South America
        REGIONS.put("SAE1", "South America (São Paulo)");
    }

    private CurNetworkUsageType() {
    }

    /**
     * Where a charge's traffic came from and went to.
     *
     * @param from  origin, or empty when the usage type does not say
     * @param to    destination, or empty when the usage type does not say
     * @param local the region the charge is billed in, which is the useful answer for traffic
     *              that never leaves it — a NAT gateway, a VPC endpoint, cross-AZ
     */
    record Route(String from, String to, String local) {

        static final Route UNKNOWN = new Route("", "", "");

        boolean isKnown() {
            return !from.isBlank() || !to.isBlank();
        }
    }

    /**
     * Derives the route from a usage type, or {@link Route#UNKNOWN} when it cannot.
     *
     * <p>The grammar, as AWS writes it:
     *
     * <pre>
     *   APS3-USE1-AWS-Out-Bytes            Mumbai      → N. Virginia   (inter-region, egress side)
     *   APS3-USE1-AWS-In-Bytes             N. Virginia → Mumbai        (the same link, ingress side)
     *   APS3-DataTransfer-Out-Bytes        Mumbai      → internet
     *   APS3-DataTransfer-In-Bytes         internet    → Mumbai
     *   APS3-DataTransfer-Regional-Bytes   within Mumbai               (crosses an AZ, not a region)
     *   APS3-NatGateway-Bytes              no route                    (see below)
     *   APS3-NatGateway-Hours              no route                    (nothing moved at all)
     *   DataTransfer-Out-Bytes             us-east-1   → internet      (no prefix means us-east-1)
     * </pre>
     *
     * <p>A missing prefix meaning us-east-1 is AWS's own documented convention, not an
     * assumption.
     *
     * <h2>Why a gateway gets no route</h2>
     * A NAT gateway, VPC endpoint or Transit Gateway charge names neither end. The bytes
     * passed <em>through</em> the gateway on their way to the internet or to an AWS service,
     * and the report does not record which — so "within Mumbai" would assert a destination
     * that is not in the data and is usually wrong, since most of that traffic leaves the
     * region entirely. The hourly component is worse: nothing moved, so a route on a
     * 652-hour line reads as traffic that never happened.
     *
     * <p>{@code Regional-Bytes} is the one within-region case that is real. That charge exists
     * precisely because the traffic crossed an availability zone inside one region, so both
     * ends genuinely are that region and "within Mumbai" is the whole story.
     */
    static Route parse(String usageType) {
        if (usageType == null || usageType.isBlank()) {
            return Route.UNKNOWN;
        }

        String[] parts = usageType.split("-");
        String local = null;
        String peer = null;
        int at = 0;
        if (parts.length > 0 && REGIONS.containsKey(parts[0].toUpperCase())) {
            local = REGIONS.get(parts[0].toUpperCase());
            at = 1;
            if (parts.length > 1 && REGIONS.containsKey(parts[1].toUpperCase())) {
                peer = REGIONS.get(parts[1].toUpperCase());
                at = 2;
            }
        }
        if (local == null) {
            // No recognised prefix at all. AWS omits it for us-east-1, but only on a usage
            // type that is otherwise a transfer — "NatGateway-Hours" is us-east-1, whereas a
            // string this function was handed by mistake should not be labelled a region.
            if (!looksLikeTransfer(usageType)) {
                return Route.UNKNOWN;
            }
            local = REGIONS.get("USE1");
            at = 0;
        }

        // Whole tokens, not substrings: "In" must not be found inside "Intra" or "Inbound",
        // which would reverse the direction of the route.
        boolean out = false;
        boolean in = false;
        for (int i = at; i < parts.length; i++) {
            if ("out".equalsIgnoreCase(parts[i])) out = true;
            if ("in".equalsIgnoreCase(parts[i])) in = true;
        }

        if (peer != null) {
            // Both ends named. "Out" is measured from the region that owns the charge, so the
            // local region is the origin; "In" is the same link seen from the other side.
            return in && !out ? new Route(peer, local, local) : new Route(local, peer, local);
        }

        if (containsIgnoreCase(usageType, "CloudFront")) {
            return out ? new Route(local, CLOUDFRONT, local) : new Route(CLOUDFRONT, local, local);
        }

        // Cross-AZ: the charge exists because the traffic crossed a zone inside one region,
        // so both ends really are that region.
        if (containsIgnoreCase(usageType, "Regional")) {
            return new Route(local, local, local);
        }

        // A gateway or endpoint is a waypoint, not a destination. See the note above: the
        // region is still reported as `local` for orientation, but claiming it as both ends of
        // a route would invent a fact the report does not contain.
        if (isWaypoint(usageType)) {
            return new Route("", "", local);
        }

        if (out) {
            return new Route(local, INTERNET, local);
        }
        if (in) {
            return new Route(INTERNET, local, local);
        }
        return new Route("", "", local);
    }

    /** True for the things traffic passes through rather than arrives at. */
    private static boolean isWaypoint(String usageType) {
        return containsIgnoreCase(usageType, "NatGateway")
                || containsIgnoreCase(usageType, "VpcEndpoint")
                || containsIgnoreCase(usageType, "PrivateLink")
                || containsIgnoreCase(usageType, "TransitGateway");
    }

    private static boolean looksLikeTransfer(String usageType) {
        return containsIgnoreCase(usageType, "DataTransfer")
                || containsIgnoreCase(usageType, "NatGateway")
                || containsIgnoreCase(usageType, "VpcEndpoint")
                || containsIgnoreCase(usageType, "PrivateLink")
                || containsIgnoreCase(usageType, "TransitGateway")
                || containsIgnoreCase(usageType, "Bytes");
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().contains(needle.toLowerCase());
    }
}
