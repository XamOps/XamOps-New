package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.xampulse.dto.XpCostPerf;
import com.xammer.cloud.xampulse.dto.XpEc2Facts;
import com.xammer.cloud.xampulse.dto.XpExposure;
import com.xammer.cloud.xampulse.dto.XpHostLimits;
import com.xammer.cloud.xampulse.service.aws.Ec2FactsService.IngressRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExposureAndCostTest {

    private static ExposureService.Listening l(String proto, int port, String bind, String addr) {
        return new ExposureService.Listening(proto, port, bind, addr);
    }

    private static IngressRule rule(String proto, Integer from, Integer to, String... sources) {
        return new IngressRule("sg-1", "web", proto, from, to, List.of(sources), null);
    }

    private static Map<Integer, XpExposure.Socket> byPort(XpExposure x) {
        Map<Integer, XpExposure.Socket> m = new java.util.HashMap<>();
        x.sockets().forEach(s -> m.put(s.port(), s));
        return m;
    }

    @Test
    void eachListeningSocketIsMatchedToTheWidestRuleThatLetsItIn() {
        List<ExposureService.Listening> socks = List.of(l("tcp", 22, "all", "0.0.0.0"), l("tcp", 22, "all", "::"), l("tcp", 443, "all", "0.0.0.0"),
                l("tcp", 6379, "all", "0.0.0.0"), l("tcp", 5432, "loopback", "127.0.0.1"), l("tcp", 8080, "address", "10.0.1.5"),
                l("tcp", 9100, "all", "0.0.0.0"));
        List<IngressRule> rules = List.of(rule("tcp", 22, 22, "203.0.113.7/32", "0.0.0.0/0"), rule("tcp", 443, 443, "0.0.0.0/0", "::/0"),
                rule("tcp", 6000, 7000, "0.0.0.0/0"), rule("tcp", 8080, 8080, "10.0.0.0/16", "sg-9"), rule("tcp", 3306, 3306, "0.0.0.0/0"));
        XpExposure x = ExposureService.evaluate("i-1", "54.1.2.3", List.of("web (sg-1)"), socks, rules, true);
        Map<Integer, XpExposure.Socket> p = byPort(x);
        assertEquals(6, x.sockets().size(), "22 on 0.0.0.0 and :: is one service");
        assertEquals("INTERNET", p.get(22).exposure());
        assertTrue(p.get(22).via().contains("0.0.0.0/0"), "the widest source is named, not the /32");
        assertEquals("INTERNET", p.get(6379).exposure(), "covered by the 6000-7000 range");
        assertEquals("LOCAL", p.get(5432).exposure(), "bound to loopback: not reachable whatever the rules say");
        assertEquals("PRIVATE", p.get(8080).exposure());
        assertEquals("BLOCKED", p.get(9100).exposure(), "listening on all interfaces but no rule lets it in");
        List<String> codes = x.findings().stream().map(XpHostLimits.Finding::code).toList();
        assertEquals(List.of("exposed_sensitive", "exposed_ssh", "sg_unused_open"), codes, "critical first; HTTPS open to the world is expected");
        assertTrue(x.findings().get(0).title().startsWith("Redis (6379)/tcp is reachable from the internet"));
        assertTrue(x.findings().get(2).title().startsWith("Port 3306/tcp is open to the internet but nothing listens"));
    }

    @Test
    void withoutAPublicIpInternetRulesDoNotReachItAndUnmeasuredMeansNothingIsCalledSafe() {
        XpExposure x = ExposureService.evaluate("i-1", null, List.of(), List.of(l("tcp", 6379, "all", "0.0.0.0")),
                List.of(rule("all", null, null, "0.0.0.0/0")), true);
        assertEquals("PRIVATE", x.sockets().get(0).exposure());
        assertTrue(x.findings().isEmpty(), "not reachable from the internet over IPv4");
        assertTrue(x.note().contains("no public IPv4"));
        XpExposure all = ExposureService.evaluate("i-1", "54.1.2.3", List.of(), List.of(), List.of(rule("all", null, null, "0.0.0.0/0")), false);
        assertFalse(all.measured());
        assertEquals("sg_all_traffic", all.findings().get(0).code(), "the rule alone is still worth saying");
        assertTrue(all.note().startsWith("The agent on this host does not report listening sockets"));
    }

    @Test
    void sourcesAreClassifiedWithoutEverResolvingANameAndCgnatIsPrivate() {
        assertEquals(ExposureService.Source.INTERNET, ExposureService.classify("::/0"));
        assertEquals(ExposureService.Source.PRIVATE, ExposureService.classify("172.16.0.0/12"));
        assertEquals(ExposureService.Source.PRIVATE, ExposureService.classify("100.64.0.0/10"));
        assertEquals(ExposureService.Source.PRIVATE, ExposureService.classify("fd12:3456::/48"));
        assertEquals(ExposureService.Source.PUBLIC_RANGE, ExposureService.classify("198.51.100.0/24"));
        assertEquals(ExposureService.Source.PRIVATE, ExposureService.classify("pl-0123abcd"));
        assertEquals(ExposureService.Source.PRIVATE, ExposureService.classify("example.com"), "not an address: never looked up");
    }

    private static XpEc2Facts facts(boolean burstable, int vcpus) {
        return new XpEc2Facts("i-1", "us-east-1", "web-1", "running", "m5.xlarge", vcpus, 16384L, "x86_64", "Linux/UNIX", burstable, null,
                null, null, null, "us-east-1a", null, null, null, null, null, null, null, null, "on-demand", null, null, null, List.of(),
                true, true, false, Map.of(), List.of(), null, List.of(), List.of(), null);
    }

    private static double[] filled(double v, int n) {
        double[] a = new double[n];
        Arrays.fill(a, v);
        return a;
    }

    @Test
    void costAgainstUseSaysWhatWasMeasuredAndWhenItIsTooEarlyToResize() {
        double[] cpu = filled(12, 100);
        cpu[99] = 60; // one busy step does not move the p95
        XpCostPerf c = CostPerformanceService.assemble("i-1", facts(false, 4), 0.192, "ON_DEMAND_LIST", cpu, filled(30, 100), Duration.ofDays(1), 300, List.of());
        assertEquals("OVERSIZED", c.verdict());
        assertEquals(12.0, c.cpuP95Pct());
        assertEquals(140.16, c.monthlyUsd());
        assertEquals(0.5, c.usedVcpusAvg(), "4 vCPUs x 12.5% average");
        assertEquals(0.384, c.costPerUsedVcpuHour());
        assertTrue(c.notes().stream().anyMatch(n -> n.startsWith("p95 is of 5-minute averages")));
        assertTrue(c.notes().stream().anyMatch(n -> n.startsWith("Based on 24 hours.")), "a day is not enough to resize on");
        assertEquals("BURSTABLE", CostPerformanceService.assemble("i-1", facts(true, 2), 0.0832, "ON_DEMAND_LIST", cpu, filled(30, 100),
                Duration.ofDays(14), 3600, List.of()).verdict());
        assertEquals("UNDERSIZED", CostPerformanceService.assemble("i-1", facts(false, 4), null, null, filled(95, 50), filled(40, 50),
                Duration.ofDays(14), 3600, List.of()).verdict());
        XpCostPerf none = CostPerformanceService.assemble("i-1", facts(false, 4), null, null, new double[0], new double[0], Duration.ofHours(1), 60, List.of());
        assertEquals("NOT_ENOUGH_DATA", none.verdict());
        assertNull(none.monthlyUsd(), "no price is never shown as $0");
    }
}
