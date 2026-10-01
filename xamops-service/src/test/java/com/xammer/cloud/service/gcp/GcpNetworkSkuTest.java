package com.xammer.cloud.service.gcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SKU description is the only thing in the GCP billing export that says what kind of network
 * charge a row is, and reading it wrong mislabels money rather than failing — so the awkward cases
 * are pinned here rather than left to a live export to reveal.
 */
class GcpNetworkSkuTest {

    private static final String MUMBAI = "asia-south1";

    @Test
    @DisplayName("The SKU names both endpoints, so the route is reported rather than inferred")
    void interRegionRouteIsReadFromTheDescription() {
        GcpNetworkSku.Route route =
                GcpNetworkSku.routeOf("Network Inter Region Egress from Mumbai to Jakarta", MUMBAI);

        assertEquals("Mumbai", route.from());
        assertEquals("Jakarta", route.to());
        assertFalse(route.derived(), "Google spelled this route out; it must not be marked inferred");
        assertEquals("Inter-region", GcpNetworkSku.categoryOf(
                "Network Inter Region Egress from Mumbai to Jakarta"));
    }

    @Test
    @DisplayName("A parenthesised qualifier is dropped so siblings merge into one route")
    void endpointQualifiersAreTrimmed() {
        GcpNetworkSku.Route route = GcpNetworkSku.routeOf(
                "Network Inter Region Egress from Mumbai to Americas (excl Oceania)", MUMBAI);

        assertEquals("Mumbai", route.from());
        assertEquals("Americas", route.to());
    }

    @Test
    @DisplayName("Cross-zone traffic has the billed region at both ends, because that is the charge")
    void interZoneStaysInsideItsRegion() {
        assertEquals("Cross-AZ", GcpNetworkSku.categoryOf("Network Inter Zone Egress"));

        GcpNetworkSku.Route route = GcpNetworkSku.routeOf("Network Inter Zone Egress", MUMBAI);
        assertEquals(MUMBAI, route.from());
        assertEquals(MUMBAI, route.to());
        assertTrue(route.derived(), "the SKU named no endpoints, so this is an inference");
    }

    @Test
    @DisplayName("A gateway is a waypoint, so no route is claimed for it")
    void gatewaysAndTunnelsClaimNoRoute() {
        // Traffic passes through these on the way somewhere the bill does not record, and the
        // uptime component moves nothing at all — naming the billed region as both ends would
        // assert a destination that is not in the data.
        for (String sku : new String[]{
                "Cloud NAT Gateway uptime",
                "Cloud NAT Data Processing",
                "Networking Private Nat Gateway Uptime",
                "Cloud VPN Tunnel",
                "Private Service Connect Data Processing",
                "Network Load Balancing: Forwarding Rule Minimum Service Charge"}) {
            assertFalse(GcpNetworkSku.routeOf(sku, MUMBAI).isKnown(),
                    sku + " must not be given a route");
        }
    }

    @Test
    @DisplayName("Cloud NAT beats the generic data-transfer arm, whatever order the words appear in")
    void natIsClassifiedBeforeGenericTransfer() {
        // "Cloud NAT Data Processing" contains "data", so a generic arm placed above the NAT one
        // would file the largest network cost on many projects under the wrong heading.
        assertEquals("NAT Gateway", GcpNetworkSku.categoryOf("Cloud NAT Data Processing"));
        assertEquals("NAT Gateway", GcpNetworkSku.categoryOf("Networking Private Nat Gateway Uptime"));
    }

    @Test
    @DisplayName("Each network family lands in its own category")
    void categoriesCoverTheNetworkFamilies() {
        assertEquals("VPC endpoint", GcpNetworkSku.categoryOf("Private Service Connect Data Processing"));
        assertEquals("Cloud VPN", GcpNetworkSku.categoryOf("Cloud VPN Tunnel"));
        assertEquals("Interconnect", GcpNetworkSku.categoryOf("Interconnect Egress from Mumbai"));
        assertEquals("Load balancing",
                GcpNetworkSku.categoryOf("HTTP Load Balancing: Inbound Data Processing"));
        assertEquals("Load balancing",
                GcpNetworkSku.categoryOf("Network Load Balancing: Forwarding Rule"));
        assertEquals("Within zone", GcpNetworkSku.categoryOf("Network Intra Zone Egress"));
        assertEquals("Inter-region",
                GcpNetworkSku.categoryOf("Networking Data Transfer inter region Intercontinental"));
    }

    @Test
    @DisplayName("Internet egress leaves the billed region; ingress arrives at it")
    void internetTransferAnchorsOnTheBilledRegion() {
        GcpNetworkSku.Route out = GcpNetworkSku.routeOf("Network Internet Egress", MUMBAI);
        assertEquals(MUMBAI, out.from());
        assertEquals(GcpNetworkSku.INTERNET, out.to());

        GcpNetworkSku.Route in = GcpNetworkSku.routeOf("Network Internet Ingress", MUMBAI);
        assertEquals(GcpNetworkSku.INTERNET, in.from());
        assertEquals(MUMBAI, in.to());
    }

    @Test
    @DisplayName("Inter-region with no named pair reports the origin only, not a guessed destination")
    void interRegionWithoutAPairIsHalfKnown() {
        // Egress is priced on the source region, so the origin is a fact. The far end is not.
        GcpNetworkSku.Route route = GcpNetworkSku.routeOf("Network Inter Region Egress", MUMBAI);
        assertEquals(MUMBAI, route.from());
        assertEquals("", route.to());
        assertTrue(route.isKnown());
    }

    @Test
    @DisplayName("Anything unrecognised falls to Other network rather than a plausible guess")
    void unknownSkusAreNotGuessed() {
        assertEquals("Other network", GcpNetworkSku.categoryOf("Nvidia Tesla T4 GPU running in Mumbai"));
        assertEquals("Other network", GcpNetworkSku.categoryOf(""));
        assertEquals("Other network", GcpNetworkSku.categoryOf(null));
        assertFalse(GcpNetworkSku.routeOf(null, MUMBAI).isKnown());
        // No region to anchor on, and the SKU named no pair: nothing to report either way.
        assertFalse(GcpNetworkSku.routeOf("Network Inter Zone Egress", "").isKnown());
    }
}
