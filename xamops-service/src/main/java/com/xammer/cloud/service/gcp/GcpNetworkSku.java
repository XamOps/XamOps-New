package com.xammer.cloud.service.gcp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the category and the route out of a GCP network SKU description.
 *
 * <p>The GCP counterpart of {@code CurNetworkUsageType}, and an easier job in one respect:
 * where AWS encodes the endpoints as region codes inside a usage type
 * ({@code APS3-USE1-AWS-Out-Bytes}), Google writes them in English —
 * "Network Inter Region Egress from Mumbai to Jakarta". So no region-code table is needed and
 * the route usually needs no inference at all.
 *
 * <p>Harder in another respect. There is no {@code from}/{@code to} column in the billing export
 * and no category column either: {@code sku.description} is the only thing that says what kind of
 * network charge a row is, the naming differs per service, and Google has renamed the family over
 * time — older SKUs say "Egress", newer ones say "Data Transfer", and the prefix varies between
 * "Network", "Networking" and the owning service. So the patterns below are keyword-based and
 * ordered, and anything unrecognised falls through to "Other network" carrying its SKU
 * description verbatim. That is deliberate: a row in the wrong bucket is invisible, whereas a row
 * in "Other network" is a visible prompt to add a pattern.
 *
 * <p>One GCP-specific fact shapes the categories. Inbound data transfer is free, and the charge
 * sits on whatever <em>processes</em> it — a load balancer, Cloud NAT, protocol forwarding. So
 * "Internet ingress" is expected to be near-empty here, and inbound cost surfaces under
 * "Load balancing" and "NAT Gateway" instead.
 *
 * @see <a href="https://cloud.google.com/vpc/network-pricing">VPC network pricing</a>
 */
final class GcpNetworkSku {

    /**
     * Which SKUs count as network charges, as a BigQuery regex over the lowercased description.
     *
     * <p>Lives beside the categoriser on purpose: a filter and a classifier that disagree produce
     * a section whose rows do not add up to its own total, and the only way to keep them in step
     * is to read them side by side.
     */
    static final String SKU_FILTER_REGEX =
            "egress|ingress|data transfer|nat gateway|nat data|network internet"
            + "|load balanc|forwarding rule|vpn tunnel|cloud vpn|interconnect"
            + "|private service connect|inter zone|inter region|intra zone|intra region";

    /** The non-Google end of an internet transfer. Matches the AWS wording so the UI reads alike. */
    static final String INTERNET = "Internet";

    /**
     * "... from Mumbai to Jakarta" — the endpoints, where the SKU names them.
     *
     * <p>Non-greedy on the origin so a description containing more than one " to " takes the last
     * as the separator rather than the first, and anchored at the end because the pair is always
     * the tail of the string.
     */
    private static final Pattern ROUTE = Pattern.compile(
            "\\bfrom\\s+(.+?)\\s+to\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    private GcpNetworkSku() {
    }

    /**
     * The category a SKU belongs to.
     *
     * <p>Ordered, first match wins, most specific first — the same discipline as the AWS CASE
     * expression and for the same reason: "Cloud NAT Data Processing" also contains "data", and a
     * generic arm placed above it would file the largest network cost on many projects under the
     * wrong heading.
     *
     * <p>The returned strings are a contract with the UI, which keys its icon and its explanation
     * off them. They are deliberately the same vocabulary the AWS section uses wherever the
     * concept is the same, so one component serves both providers: a GCP zone is an AWS
     * availability zone, and Cloud NAT is a NAT gateway.
     */
    static String categoryOf(String skuDescription) {
        String sku = skuDescription == null ? "" : skuDescription.toLowerCase();

        if (sku.contains("nat gateway") || sku.contains("nat data")) {
            return "NAT Gateway";
        }
        // Private Service Connect is GCP's VPC endpoint: the same fix for the same problem, so it
        // belongs in the same row of the comparison.
        if (sku.contains("private service connect")) {
            return "VPC endpoint";
        }
        if (sku.contains("vpn tunnel") || sku.contains("cloud vpn")) {
            return "Cloud VPN";
        }
        if (sku.contains("interconnect")) {
            return "Interconnect";
        }
        // Load balancing and protocol forwarding are where GCP puts the cost of inbound traffic,
        // since ingress itself is free. Categorising them as network spend rather than as compute
        // is the whole reason this section can show a total the service breakdown cannot.
        if (sku.contains("load balanc") || sku.contains("forwarding rule")) {
            return "Load balancing";
        }
        if (sku.contains("inter zone")) {
            return "Cross-AZ";
        }
        if (sku.contains("inter region") || sku.contains("intercontinental")) {
            return "Inter-region";
        }
        if (sku.contains("intra zone") || sku.contains("intra region")) {
            return "Within zone";
        }
        if (sku.contains("ingress") || sku.contains("data transfer in")) {
            return "Internet ingress";
        }
        // Everything left that is egress or transfer out leaves Google's network. Reached only
        // after the zone and region arms above, so an unqualified "data transfer out" really is
        // internet-bound rather than an internal hop that named itself vaguely.
        if (sku.contains("egress") || sku.contains("data transfer")) {
            return "Internet egress";
        }
        return "Other network";
    }

    /**
     * Where the traffic went, and whether the SKU said so or we worked it out.
     *
     * @param from    origin, or empty when neither the SKU nor the category implies one
     * @param to      destination, on the same terms
     * @param derived false when the SKU description named both ends itself
     */
    record Route(String from, String to, boolean derived) {

        static final Route UNKNOWN = new Route("", "", false);

        /** True when at least one end is named. A half-known route is still worth drawing. */
        boolean isKnown() {
            return !from.isBlank() || !to.isBlank();
        }
    }

    /**
     * The route for one SKU, using the billed region as the anchor for traffic that stays inside it.
     *
     * <p>An explicit "from X to Y" in the description always wins and is reported as first-hand.
     * Otherwise the category implies as much as it honestly can:
     *
     * <ul>
     *   <li>cross-zone traffic has the billed region at both ends, which is the whole of the
     *       story — the charge exists <em>because</em> it crossed a zone inside that region;</li>
     *   <li>internet egress leaves the billed region for the internet, and ingress arrives from
     *       it, because Google prices egress on the source region;</li>
     *   <li>a gateway, endpoint, load balancer, VPN or interconnect gets no route at all. Traffic
     *       passes <em>through</em> these on the way somewhere the bill does not record, and the
     *       uptime component moves nothing whatsoever — so naming the billed region as both ends
     *       would assert a destination that is not in the data. Same decision as the AWS side.</li>
     * </ul>
     *
     * @param region the row's {@code location.region}, or blank when the charge has none
     */
    static Route routeOf(String skuDescription, String region) {
        if (skuDescription == null || skuDescription.isBlank()) {
            return Route.UNKNOWN;
        }

        Matcher named = ROUTE.matcher(skuDescription);
        if (named.find()) {
            return new Route(tidy(named.group(1)), tidy(named.group(2)), false);
        }

        String local = region == null || region.isBlank() ? "" : region;
        if (local.isEmpty()) {
            return Route.UNKNOWN;
        }

        switch (categoryOf(skuDescription)) {
            case "Cross-AZ":
            case "Within zone":
                return new Route(local, local, true);
            case "Internet egress":
                return new Route(local, INTERNET, true);
            case "Internet ingress":
                return new Route(INTERNET, local, true);
            case "Inter-region":
                // Egress is priced on the source region, so the origin is known and the far end is
                // not. Half a route is still worth showing; inventing the other half is not.
                return new Route(local, "", true);
            default:
                return Route.UNKNOWN;
        }
    }

    /**
     * Trims a captured endpoint and drops a trailing qualifier Google appends in parentheses,
     * so "Americas (excl Oceania)" reads as "Americas" and merges with its siblings.
     */
    private static String tidy(String endpoint) {
        String trimmed = endpoint.trim();
        int parenthesis = trimmed.indexOf('(');
        if (parenthesis > 0) {
            trimmed = trimmed.substring(0, parenthesis).trim();
        }
        return trimmed;
    }
}
