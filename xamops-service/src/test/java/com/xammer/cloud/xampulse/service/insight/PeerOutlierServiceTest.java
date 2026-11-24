package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.xampulse.dto.XpPeers;
import com.xammer.cloud.xampulse.service.aws.Ec2FactsService.PeerKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PeerOutlierServiceTest {

    private static PeerKey k(String id, String type, String asg, String name) {
        return new PeerKey(id, type, asg, name);
    }

    @Test
    void namePatternsDropTheTrailingNumberOrId() {
        assertEquals("web", PeerOutlierService.namePattern("web-3"));
        assertEquals("web", PeerOutlierService.namePattern("Web-12"));
        assertEquals("api-prod", PeerOutlierService.namePattern("api-prod-7f3a9c"));
        assertEquals("db", PeerOutlierService.namePattern("db"));
        assertNull(PeerOutlierService.namePattern("  "));
        assertNull(PeerOutlierService.namePattern(null));
    }

    @Test
    void theAutoScalingGroupWinsThenTypeAndNameNeverAnUnrelatedHost() {
        PeerKey me = k("i-1", "m5.large", "web-asg", "web-1");
        List<PeerKey> all = List.of(me, k("i-2", "m5.xlarge", "web-asg", "web-2"), k("i-3", "m5.large", null, "web-3"), k("i-4", "m5.large", null, "db-1"));
        PeerOutlierService.Choice asg = PeerOutlierService.choose(me, all);
        assertEquals("ASG", asg.basis());
        assertEquals(List.of("i-2"), asg.peers().stream().map(PeerKey::instanceId).toList(), "group members, whatever their type");

        PeerKey loner = k("i-3", "m5.large", null, "web-3");
        PeerOutlierService.Choice twin = PeerOutlierService.choose(loner, all);
        assertEquals("TYPE_NAME", twin.basis());
        assertEquals(List.of("i-1"), twin.peers().stream().map(PeerKey::instanceId).toList(), "same type and pattern; db-1 and the xlarge are not twins");
        assertEquals("NONE", PeerOutlierService.choose(k("i-9", "c5.large", null, null), all).basis(), "no name, no ASG: no peers");
    }

    private static List<PeerKey> peers(String... ids) {
        return java.util.Arrays.stream(ids).map(i -> k(i, "t", null, "x")).toList();
    }

    @Test
    void oneHotHostAmongCalmPeersIsAnOutlierEvenIfOnePeerIsOdd() {
        Map<String, Double> cpu = Map.of("me", 82.0, "a", 30.0, "b", 34.0, "c", 31.0, "d", 95.0);
        XpPeers.Metric m = PeerOutlierService.compare("cpu", "CPU busy", "%", "me", cpu, peers("a", "b", "c", "d"), 5);
        assertEquals("HIGHER", m.verdict(), "the median (32.5) and MAD ignore the one busy peer");
        assertEquals(32.5, m.peerMedian());
        assertFalse(m.indicative());
        assertTrue(m.z() > 3.5);
    }

    @Test
    void smallDifferencesBetweenIdleHostsAreNotOutliers() {
        // Peers identical (MAD 0): a 3-point difference must not become an infinite z.
        Map<String, Double> cpu = Map.of("me", 5.0, "a", 2.0, "b", 2.0, "c", 2.0);
        assertEquals("IN_LINE", PeerOutlierService.compare("cpu", "CPU busy", "%", "me", cpu, peers("a", "b", "c"), 5).verdict());
        Map<String, Double> far = Map.of("me", 9.0, "a", 2.0, "b", 2.0, "c", 2.0);
        assertEquals("HIGHER", PeerOutlierService.compare("cpu", "CPU busy", "%", "me", far, peers("a", "b", "c"), 5).verdict(),
                "7 points above identical peers: a real difference");
    }

    @Test
    void twoPeersGiveAnIndicativeVerdictOneGivesNoneAndMissingDataIsSaid() {
        XpPeers.Metric two = PeerOutlierService.compare("cpu", "CPU busy", "%", "me", Map.of("me", 78.0, "a", 34.0, "b", 38.0), peers("a", "b"), 5);
        assertEquals("HIGHER", two.verdict());
        assertTrue(two.indicative());
        assertEquals("TOO_FEW", PeerOutlierService.compare("cpu", "CPU busy", "%", "me", Map.of("me", 78.0, "a", 34.0), peers("a", "b"), 5).verdict());
        assertEquals("NO_DATA", PeerOutlierService.compare("cpu", "CPU busy", "%", "me", Map.of("a", 34.0, "b", 30.0), peers("a", "b"), 5).verdict());
        assertEquals("LOWER", PeerOutlierService.compare("network", "Network", "B/s", "me",
                Map.of("me", 0.0, "a", 9e7, "b", 9.2e7, "c", 8.9e7), peers("a", "b", "c"), 1 << 20).verdict(), "a host that stopped serving");
    }
}
