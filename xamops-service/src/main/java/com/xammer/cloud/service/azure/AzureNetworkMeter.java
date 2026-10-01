package com.xammer.cloud.service.azure;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the category and the route out of an Azure meter.
 *
 * <p>The Azure counterpart of {@code CurNetworkUsageType} and {@link GcpNetworkSku}, and a harder
 * job than either. AWS encodes the endpoints as region codes inside one usage type
 * ({@code APS3-USE1-AWS-Out-Bytes}) and Google writes them in English inside one SKU description
 * ("Network Inter Region Egress from Mumbai to Jakarta"). Azure splits the same information across
 * three columns that each carry part of it and none of which is reliably populated:
 * {@code MeterCategory} says which service billed it ("Bandwidth", "Virtual Network", "NAT
 * Gateway"), {@code MeterSubCategory} sometimes narrows it, and {@code Meter} carries the rest
 * ("Inter Region - Data Transfer Out", "Data Transfer In", "Standard Data Transfer Out - Zone 1").
 * So the classifier is given all three and matches across them.
 *
 * <p>There is no from/to column anywhere in Cost Management, which is why every route this produces
 * is reported as derived. Azure's zone naming is also not a place: "Zone 1" in a bandwidth meter is
 * a <em>pricing</em> zone — a group of countries — not an availability zone, and reading it as one
 * would put "Zone 1 → Zone 2" on screen as though it described a region pair. It is deliberately
 * not treated as a route.
 *
 * <p>Anything unrecognised falls through to "Other network" carrying its meter name verbatim,
 * rather than being dropped. A row in the wrong bucket is invisible; a row in "Other network" is a
 * visible prompt to add a pattern.
 *
 * <p>The category strings are a contract with {@code DataTransferSection}, which keys its icon and
 * its explanation off them. They are the same vocabulary the AWS and GCP sections use wherever the
 * concept is the same — an Azure availability zone is an AWS availability zone, a Private Endpoint
 * is a VPC endpoint, ExpressRoute is an interconnect — so one component serves all three providers.
 *
 * @see <a href="https://azure.microsoft.com/pricing/details/bandwidth/">Bandwidth pricing</a>
 */
final class AzureNetworkMeter {

    /** The non-Azure end of an internet transfer. Matches the AWS wording so the UI reads alike. */
    static final String INTERNET = "Internet";

    /**
     * Meter categories that are network charges in their entirety.
     *
     * <p>Every meter under these belongs in this section whatever it is called, including the
     * hourly fees: a gateway billed per hour with nothing flowing through it is precisely the
     * finding this section exists to surface, and filtering on the word "transfer" would hide it.
     */
    private static final java.util.Set<String> NETWORK_CATEGORIES = java.util.Set.of(
            "bandwidth",
            "virtual network",
            "vpn gateway",
            "nat gateway",
            "expressroute",
            "azure expressroute",
            "load balancer",
            "application gateway",
            "azure front door service",
            "azure front door",
            "content delivery network",
            "azure cdn",
            "traffic manager",
            "azure dns",
            "virtual wan",
            "azure firewall",
            "azure firewall manager",
            "private link",
            "azure private link",
            "azure bastion",
            "network watcher",
            "routing preference");

    /**
     * Meters that are network charges even though their category is something else.
     *
     * <p>Storage geo-replication is the case that matters: it is billed under "Storage" and is
     * inter-region traffic by definition, so a section that only read the network categories would
     * miss one of the larger cross-region costs on a replicated account.
     */
    private static final Pattern NETWORK_METER = Pattern.compile(
            "data\\s*transfer|geo[\\s-]*replication|egress|ingress|peering|bandwidth"
                    + "|inter[\\s-]*region|intra[\\s-]*region|inter[\\s-]*continental"
                    + "|data\\s*processed|private\\s*endpoint|private\\s*link",
            Pattern.CASE_INSENSITIVE);

    /**
     * "Inter Region - Data Transfer Out - Asia Pacific to Europe" — the endpoints, where a meter
     * happens to name them.
     *
     * <p>Rare on Azure, unlike GCP where it is the norm. Matched anyway because when it is present
     * it is the only first-hand route information Cost Management carries, and inferring over the
     * top of it would be strictly worse.
     */
    private static final Pattern ROUTE = Pattern.compile(
            "\\b(?:from\\s+)?([A-Za-z][A-Za-z ]+?)\\s+to\\s+([A-Za-z][A-Za-z ]+?)\\s*$");

    private AzureNetworkMeter() {
    }

    /** Whether a billed line is a network charge at all. */
    static boolean isNetwork(String meterCategory, String meterSubCategory, String meter) {
        String category = lower(meterCategory);
        if (NETWORK_CATEGORIES.contains(category)) {
            return true;
        }
        return NETWORK_METER.matcher(text(meterCategory, meterSubCategory, meter)).find();
    }

    /**
     * The category a billed line belongs to.
     *
     * <p>Ordered, first match wins, most specific first — the same discipline as the AWS CASE
     * expression and for the same reason. "NAT gateway data processed" also contains "data
     * processed", and a generic arm placed above it would file the largest network cost on many
     * subscriptions under the wrong heading.
     *
     * <p>The meter category is consulted before the meter name because Azure reuses meter names
     * across services: "Data Processed" belongs to a NAT gateway, a load balancer, a firewall and
     * an application gateway, and only the category says which.
     */
    static String categoryOf(String meterCategory, String meterSubCategory, String meter) {
        String category = lower(meterCategory);
        String all = lower(text(meterCategory, meterSubCategory, meter));

        // ── Decided by which service billed it ──────────────────────────────────
        if (category.contains("nat gateway")) {
            return "NAT Gateway";
        }
        if (category.contains("private link") || all.contains("private endpoint")) {
            return "VPC endpoint";
        }
        if (category.contains("vpn gateway")) {
            return "Cloud VPN";
        }
        if (category.contains("expressroute")) {
            return "Interconnect";
        }
        if (category.contains("load balancer") || category.contains("application gateway")
                || category.contains("traffic manager") || category.contains("front door")
                || category.contains("azure firewall") || category.contains("virtual wan")
                || category.contains("bastion")) {
            return "Load balancing";
        }

        // ── Decided by what the meter says ──────────────────────────────────────
        // Checked before the generic egress arm: a CDN's "Data Transfer Out" is internet-bound,
        // but so is an inter-region hop's, and only the ordering keeps them apart.
        if (all.contains("inter region") || all.contains("inter-region")
                || all.contains("intercontinental") || all.contains("inter continental")
                || all.contains("inter-continental") || all.contains("geo-replication")
                || all.contains("geo replication")
                // Global VNet peering crosses regions; intra-region peering does not, and is
                // handled by the arm below.
                || (all.contains("peering") && all.contains("global"))) {
            return "Inter-region";
        }
        // Azure prices availability-zone traffic under "Intra-Region" and "Availability Zone".
        // Not to be confused with a bandwidth meter's "Zone 1", which is a group of countries —
        // see the class comment.
        if (all.contains("intra region") || all.contains("intra-region")
                || all.contains("availability zone") || all.contains("zonal")
                || (all.contains("peering") && !all.contains("global"))) {
            return "Cross-AZ";
        }
        if (all.contains("data transfer in") || all.contains("ingress")) {
            return "Internet ingress";
        }
        if (all.contains("data transfer out") || all.contains("egress")
                || category.contains("content delivery network") || category.contains("azure cdn")
                || category.contains("bandwidth")) {
            return "Internet egress";
        }
        return "Other network";
    }

    /**
     * Where the traffic went, and whether the meter said so or it was worked out.
     *
     * @param from    origin, or empty when neither the meter nor the category implies one
     * @param to      destination, on the same terms
     * @param derived always true on Azure except for the rare meter that names both ends, because
     *                Cost Management carries no from/to column at all
     */
    record Route(String from, String to, boolean derived) {

        static final Route UNKNOWN = new Route("", "", false);

        /** True when at least one end is named. A half-known route is still worth drawing. */
        boolean isKnown() {
            return !from.isBlank() || !to.isBlank();
        }
    }

    /**
     * The route for one billed line, using the billed region as the anchor.
     *
     * <p>An explicit "X to Y" in the meter name always wins and is reported as first-hand.
     * Otherwise the category implies as much as it honestly can:
     *
     * <ul>
     *   <li>zone-crossing traffic has the billed region at both ends, which is the whole story —
     *       the charge exists <em>because</em> it crossed a zone inside that region;</li>
     *   <li>egress leaves the billed region for the internet, and ingress arrives from it, because
     *       Azure prices transfer on the source region;</li>
     *   <li>inter-region egress knows its origin and not its destination, so half a route is
     *       reported rather than a guessed pair;</li>
     *   <li>a gateway, endpoint, load balancer, VPN or ExpressRoute circuit gets no route at all.
     *       Traffic passes <em>through</em> these to somewhere the bill does not record, and the
     *       hourly component moves nothing whatsoever — so naming the billed region as both ends
     *       would assert a destination that is not in the data. Same decision as AWS and GCP.</li>
     * </ul>
     *
     * @param region the row's {@code ResourceLocation}, or blank when the charge has none
     */
    static Route routeOf(String meterCategory, String meterSubCategory, String meter,
                         String region) {
        String all = text(meterCategory, meterSubCategory, meter);
        if (all.isBlank()) {
            return Route.UNKNOWN;
        }

        Matcher named = ROUTE.matcher(meter == null ? "" : meter.trim());
        if (named.find()) {
            String from = tidy(named.group(1));
            String to = tidy(named.group(2));
            // "Zone 1 to Zone 2" is a price band pair, not a place pair. See the class comment.
            if (!isPricingZone(from) && !isPricingZone(to)) {
                return new Route(from, to, false);
            }
        }

        String local = region == null || region.isBlank() ? "" : region;
        if (local.isEmpty()) {
            return Route.UNKNOWN;
        }

        return switch (categoryOf(meterCategory, meterSubCategory, meter)) {
            case "Cross-AZ", "Within zone" -> new Route(local, local, true);
            case "Internet egress" -> new Route(local, INTERNET, true);
            case "Internet ingress" -> new Route(INTERNET, local, true);
            case "Inter-region" -> new Route(local, "", true);
            default -> Route.UNKNOWN;
        };
    }

    /** "Zone 1", "Zone 2" — Azure's bandwidth price bands, which name no place. */
    private static boolean isPricingZone(String endpoint) {
        return endpoint.toLowerCase().matches("zone\\s*\\d+");
    }

    /** The three columns as one string, so a pattern can be written once instead of three times. */
    private static String text(String meterCategory, String meterSubCategory, String meter) {
        return (blank(meterCategory) + " " + blank(meterSubCategory) + " " + blank(meter)).trim();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    /** Trims a captured endpoint and drops a trailing parenthesised qualifier. */
    private static String tidy(String endpoint) {
        String trimmed = endpoint.trim();
        int parenthesis = trimmed.indexOf('(');
        if (parenthesis > 0) {
            trimmed = trimmed.substring(0, parenthesis).trim();
        }
        return trimmed;
    }
}
