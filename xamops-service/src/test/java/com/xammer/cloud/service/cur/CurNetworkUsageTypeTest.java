package com.xammer.cloud.service.cur;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The usage type is the only place a route survives on a report without the location
 * columns, and reading it wrong names the wrong region rather than failing — which is why
 * the awkward cases are pinned down here rather than left to a live report to reveal.
 */
class CurNetworkUsageTypeTest {

    @Test
    @DisplayName("Two region codes give a route, and In reverses it")
    void interRegionRoutesReadBothEnds() {
        CurNetworkUsageType.Route out = CurNetworkUsageType.parse("APS3-USE1-AWS-Out-Bytes");
        assertEquals("Asia Pacific (Mumbai)", out.from());
        assertEquals("US East (N. Virginia)", out.to());

        // The same link seen from the other side: "In" is measured into the region that owns
        // the charge, so the peer is the origin.
        CurNetworkUsageType.Route in = CurNetworkUsageType.parse("APS3-USE1-AWS-In-Bytes");
        assertEquals("US East (N. Virginia)", in.from());
        assertEquals("Asia Pacific (Mumbai)", in.to());
    }

    @Test
    @DisplayName("APS3 is Mumbai, not Jakarta — the mapping everyone gets wrong")
    void mumbaiIsNotJakarta() {
        assertEquals("Asia Pacific (Mumbai)",
                CurNetworkUsageType.parse("APS3-DataTransfer-Regional-Bytes").local());
        assertEquals("Asia Pacific (Jakarta)",
                CurNetworkUsageType.parse("APS4-DataTransfer-Regional-Bytes").local());
        assertEquals("Asia Pacific (Singapore)",
                CurNetworkUsageType.parse("APS1-DataTransfer-Regional-Bytes").local());
    }

    @Test
    @DisplayName("Egress goes to the internet, ingress comes from it")
    void internetTransferNamesTheInternetEnd() {
        CurNetworkUsageType.Route out = CurNetworkUsageType.parse("APS3-DataTransfer-Out-Bytes");
        assertEquals("Asia Pacific (Mumbai)", out.from());
        assertEquals(CurNetworkUsageType.INTERNET, out.to());

        CurNetworkUsageType.Route in = CurNetworkUsageType.parse("APS3-DataTransfer-In-Bytes");
        assertEquals(CurNetworkUsageType.INTERNET, in.from());
        assertEquals("Asia Pacific (Mumbai)", in.to());
    }

    @Test
    @DisplayName("Cross-AZ really is within one region, so both ends are that region")
    void crossAzTrafficReportsTheRegionAtBothEnds() {
        CurNetworkUsageType.Route route = CurNetworkUsageType.parse("APS3-DataTransfer-Regional-Bytes");
        assertEquals("Asia Pacific (Mumbai)", route.from());
        assertEquals("Asia Pacific (Mumbai)", route.to());
    }

    @Test
    @DisplayName("A gateway is a waypoint, so no route is claimed for it")
    void gatewayChargesClaimNoRoute() {
        // The bytes passed through the gateway on their way somewhere the report does not
        // record — usually out of the region entirely — so "within Mumbai" would be a
        // destination we invented. The hourly line is worse: nothing moved at all.
        for (String usageType : new String[]{
                "APS3-NatGateway-Bytes",
                "APS3-NatGateway-Hours",
                "APS3-VpcEndpoint-Bytes",
                "APS3-VpcEndpoint-Hours",
                "APS3-TransitGateway-Bytes"}) {
            CurNetworkUsageType.Route route = CurNetworkUsageType.parse(usageType);
            assertFalse(route.isKnown(), usageType + " must not be given a route");
            // The region is still known and still worth reporting for orientation.
            assertEquals("Asia Pacific (Mumbai)", route.local(), usageType);
        }
    }

    @Test
    @DisplayName("A missing region prefix means us-east-1, which is AWS's own convention")
    void noPrefixMeansNorthVirginia() {
        CurNetworkUsageType.Route route = CurNetworkUsageType.parse("DataTransfer-Out-Bytes");
        assertEquals("US East (N. Virginia)", route.from());
        assertEquals(CurNetworkUsageType.INTERNET, route.to());
    }

    @Test
    @DisplayName("EU without a digit is Ireland, and EUW1 is accepted for the same region")
    void irelandHasTwoSpellings() {
        assertEquals("Europe (Ireland)",
                CurNetworkUsageType.parse("EU-DataTransfer-Out-Bytes").from());
        assertEquals("Europe (Ireland)",
                CurNetworkUsageType.parse("APS3-EUW1-AWS-Out-Bytes").to());
    }

    @Test
    @DisplayName("Nothing recognisable yields no route rather than a guessed one")
    void unrelatedUsageTypesAreNotRouted() {
        assertFalse(CurNetworkUsageType.parse("").isKnown());
        assertFalse(CurNetworkUsageType.parse(null).isKnown());
        // A storage charge that happens to reach this code must not be labelled us-east-1.
        assertFalse(CurNetworkUsageType.parse("TimedStorage-ByteHrs").isKnown());
        // A recognised region with nothing directional after it: the region is known, the
        // route is not, and only the route is reported as absent.
        CurNetworkUsageType.Route route = CurNetworkUsageType.parse("APS3-Requests-Tier1");
        assertFalse(route.isKnown());
        assertEquals("Asia Pacific (Mumbai)", route.local());
    }

    @Test
    @DisplayName("Direction is matched on whole tokens, so Intra is not read as In")
    void directionDoesNotMatchSubstrings() {
        // Were "In" matched as a substring it would be found inside "Intra" and reverse the
        // route, silently swapping origin and destination.
        CurNetworkUsageType.Route route = CurNetworkUsageType.parse("APS3-DataTransfer-Intra-Out-Bytes");
        assertEquals("Asia Pacific (Mumbai)", route.from());
        assertEquals(CurNetworkUsageType.INTERNET, route.to());
        assertTrue(route.isKnown());
    }
}
