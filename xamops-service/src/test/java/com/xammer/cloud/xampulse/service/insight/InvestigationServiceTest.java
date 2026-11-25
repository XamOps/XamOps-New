package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.xampulse.dto.XpChange;
import com.xammer.cloud.xampulse.dto.XpHostLimits;
import com.xammer.cloud.xampulse.dto.XpHostSummary;
import com.xammer.cloud.xampulse.dto.XpInvestigation.Hypothesis;
import com.xammer.cloud.xampulse.dto.XpPattern;
import com.xammer.cloud.xampulse.dto.XpPeers;
import com.xammer.cloud.xampulse.dto.XpSeverity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InvestigationServiceTest {

    private static final Instant FROM = Instant.parse("2026-11-13T10:00:00Z");
    private static final Instant TO = FROM.plusSeconds(3600);

    private static List<double[]> flat(double v, int n) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new double[] {FROM.toEpochMilli() + i * 60_000L, v});
        }
        return out;
    }

    private static XpHostSummary summary(double cpu, double mem, double fs) {
        Map<String, List<double[]>> s = new LinkedHashMap<>();
        s.put("system.cpu.utilization", flat(cpu, 60));
        s.put("system.memory.utilization", flat(mem, 60));
        s.put("system.filesystem.utilization{mountpoint=/var}", flat(fs, 60));
        return new XpHostSummary(null, List.of(), s, List.of(), List.of(), List.of());
    }

    private static final List<XpHostLimits.Signal> ALL_OK = List.of(
            new XpHostLimits.Signal("ena", "AWS network allowances", "OK", null),
            new XpHostLimits.Signal("ebs", "EBS volume and instance limits", "NOT_MEASURED", "Press Refresh in the AWS section."),
            new XpHostLimits.Signal("spot", "Spot notices", "OK", null),
            new XpHostLimits.Signal("psi", "Pressure", "OK", null),
            new XpHostLimits.Signal("steal", "CPU steal", "OK", null),
            new XpHostLimits.Signal("container_cpu", "Container CPU limits", "OK", null));

    private static Map<String, Hypothesis> byCode(List<Hypothesis> hs) {
        Map<String, Hypothesis> m = new LinkedHashMap<>();
        hs.forEach(h -> m.put(h.code(), h));
        return m;
    }

    @Test
    void aDeployFollowedByACpuJumpAndNewErrorsLeadsAndTheRestIsRuledOutOrUnknown() {
        XpChange deploy = new XpChange(FROM.plusSeconds(1200), "i-1", "DEPLOY", "api redeployed (api:1 → api:2)", "", "AGENT", "api",
                "FOLLOWED_BY_CHANGE", false, List.of(new XpChange.Effect("container_cpu", "container CPU", "%", 21, 81, 2.9, "STRONG")));
        XpPattern fresh = new XpPattern("p1", "NullPointerException at OrderService.<*>", 42, null, true, List.of(), FROM.plusSeconds(1300), "s", 1L, XpSeverity.ERROR);
        InvestigationService.Evidence e = new InvestigationService.Evidence(new XpHostLimits("i-1", FROM, TO, List.of(), ALL_OK),
                List.of(deploy), summary(55, 60, 41), new InvestigationService.ErrorRates(4.2, 0.1, 5000, 4000), List.of(fresh), null);
        List<Hypothesis> hs = InvestigationService.evaluate(e, FROM, TO, "i-1");
        Map<String, Hypothesis> h = byCode(hs);

        assertEquals("change", hs.get(0).code(), "strongest lead first");
        assertEquals("SUPPORTED", h.get("change").status());
        assertEquals("HIGH", h.get("change").confidence());
        assertEquals("api redeployed (api:1 → api:2) at 10:20 UTC: followed by container CPU 21% → 81%", h.get("change").evidence().get(0));
        assertEquals("SUPPORTED", h.get("errors").status());
        assertEquals("instance:i-1 severity:>=error", h.get("errors").logsQuery());
        assertTrue(h.get("errors").evidence().get(1).startsWith("New error: NullPointerException"));
        assertEquals("RULED_OUT", h.get("aws_limit").status());
        assertTrue(h.get("aws_limit").evidence().contains("EBS volume and instance limits: not measured (Press Refresh in the AWS section.)"),
                "a check that could not run is said, never shown as fine");
        assertEquals("RULED_OUT", h.get("saturation").status());
        assertEquals("NOT_MEASURABLE", h.get("host_specific").status(), "peers could not be read");

        String s = InvestigationService.summarise(hs);
        assertTrue(s.startsWith("Strongest lead: something changed just before. api redeployed"), s);
        assertTrue(s.contains("Also seen: the application started failing."), s);
        assertTrue(s.contains("Ruled out: AWS capped the instance;"), "acronyms keep their case: " + s);
        assertTrue(s.contains("Cannot tell here: only this host is affected."), s);
        assertFalse(s.toLowerCase().contains("caused"), "never claims a cause");
    }

    @Test
    void aCriticalAwsLimitOutranksEverythingAndSaturationReadsTheSeries() {
        XpHostLimits limits = new XpHostLimits("i-1", FROM, TO, List.of(
                new XpHostLimits.Finding("CRITICAL", "spot_interruption", "AWS", "EC2 will terminate this Spot instance at 10:42", "", "", Map.of()),
                new XpHostLimits.Finding("WARNING", "psi_memory_full", "KERNEL", "Every task stalled on memory", "", "", Map.of("stalled_share", 0.12))), ALL_OK);
        InvestigationService.Evidence e = new InvestigationService.Evidence(limits, List.of(), summary(97, 96, 93),
                new InvestigationService.ErrorRates(0.2, 0.2, 100, 100), List.of(), null);
        Map<String, Hypothesis> h = byCode(InvestigationService.evaluate(e, FROM, TO, "i-1"));
        assertEquals(100, h.get("aws_limit").weight());
        assertEquals("SUPPORTED", h.get("kernel_pressure").status());
        assertEquals("Every task stalled on memory (stalled share 0.12)", h.get("kernel_pressure").evidence().get(0));
        Hypothesis sat = h.get("saturation");
        assertEquals("SUPPORTED", sat.status());
        assertEquals(List.of("CPU at or above 90% for 100% of the range (peak 97%)", "Memory used reached 96%", "/var is 93% full"), sat.evidence());
        assertEquals("RULED_OUT", h.get("errors").status());
        assertEquals("RULED_OUT", h.get("change").status());
    }

    @Test
    void peersSayWhetherItIsThisHostOrTheFleet() {
        XpPeers odd = new XpPeers("i-1", "ASG", "Auto Scaling group web", List.of(new XpPeers.Peer("i-2", "web-2"), new XpPeers.Peer("i-3", "web-3"),
                new XpPeers.Peer("i-4", "web-4")), List.of(new XpPeers.Metric("cpu", "CPU busy", "%", 82.0, 32.5, 30.0, 34.0, 12.0, "HIGHER", false, Map.of())), null);
        Hypothesis h = InvestigationService.hostSpecific(odd);
        assertEquals("SUPPORTED", h.status());
        assertEquals("CPU busy 82% vs 32.5% on its 3 peers (Auto Scaling group web)", h.evidence().get(0));
        XpPeers same = new XpPeers("i-1", "ASG", "Auto Scaling group web", odd.peers(),
                List.of(new XpPeers.Metric("cpu", "CPU busy", "%", 33.0, 32.5, 30.0, 34.0, 0.2, "IN_LINE", false, Map.of())), null);
        assertEquals("RULED_OUT", InvestigationService.hostSpecific(same).status());
        assertTrue(InvestigationService.hostSpecific(same).evidence().get(0).contains("shared cause"));
    }

    @Test
    void noLogsMeansErrorsCannotBeJudgedAndUnreadableSourcesAreUnknownNotFine() {
        assertEquals("NOT_MEASURABLE", InvestigationService.errors(new InvestigationService.ErrorRates(0, 0, 0, 0), List.of(), "i-1").status());
        InvestigationService.Evidence nothing = new InvestigationService.Evidence(null, null, null, null, null, null);
        List<Hypothesis> hs = InvestigationService.evaluate(nothing, FROM, TO, "i-1");
        assertTrue(hs.stream().allMatch(x -> "NOT_MEASURABLE".equals(x.status())), "nothing could be read: nothing is ruled out");
        assertTrue(InvestigationService.summarise(hs).startsWith("Nothing stands out in this range. Cannot tell here:"));
    }
}
