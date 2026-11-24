package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.xampulse.dto.XpEc2Facts;
import com.xammer.cloud.xampulse.dto.XpHostLimits;
import com.xammer.cloud.xampulse.dto.XpHostLimits.Finding;
import com.xammer.cloud.xampulse.service.insight.ThrottleRadarService.Inc;
import com.xammer.cloud.xampulse.service.insight.ThrottleRadarService.Inputs;
import com.xammer.cloud.xampulse.service.insight.ThrottleRadarService.Latest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ThrottleRadarServiceTest {

    private static final Instant FROM = Instant.parse("2026-11-12T10:00:00Z");
    private static final Instant TO = FROM.plusSeconds(3600);

    private static Inputs empty() {
        return new Inputs(Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of(), null, null, null, null, Map.of(), Map.of(), null);
    }

    private static XpHostLimits run(Inputs in, XpEc2Facts facts) {
        return ThrottleRadarService.evaluate("i-1", FROM, TO, in, facts);
    }

    private static Map<String, String> states(XpHostLimits l) {
        return l.signals().stream().collect(Collectors.toMap(XpHostLimits.Signal::code, XpHostLimits.Signal::state));
    }

    private static Finding only(XpHostLimits l, String code) {
        List<Finding> f = l.findings().stream().filter(x -> x.code().equals(code)).toList();
        assertEquals(1, f.size(), code + " in " + l.findings());
        return f.get(0);
    }

    @Test
    void nothingReportedIsNotMeasuredWithAReasonNeverHealthy() {
        XpHostLimits l = run(empty(), null);
        assertTrue(l.findings().isEmpty());
        assertEquals(7, l.signals().size());
        for (XpHostLimits.Signal s : l.signals()) {
            assertEquals("NOT_MEASURED", s.state(), s.code());
            assertNotNull(s.note(), s.code() + " must say why it is not measured");
        }
    }

    @Test
    void anyEnaAllowancePacketIsAnAwsLimitWithItsCountAndFix() {
        Inputs in = new Inputs(Map.of("pps", new Inc(4210, 3600), "bw_in", new Inc(0, 3600)), Map.of(), Map.of(), null,
                Map.of(), Map.of(), null, null, null, null, Map.of(), Map.of(), null);
        XpHostLimits l = run(in, null);
        Finding f = only(l, "ena_pps");
        assertEquals("AWS", f.source());
        assertTrue(f.title().contains("4,210"), f.title());
        assertTrue(f.title().contains("packets-per-second"), f.title());
        assertEquals(4210.0, f.evidence().get("packets"));
        assertTrue(l.findings().stream().noneMatch(x -> x.code().equals("ena_bw_in")), "zero increase is not a finding");
        assertEquals("LIMITED", states(l).get("ena"));

        XpHostLimits ok = run(new Inputs(Map.of("pps", new Inc(0, 3600)), Map.of(), Map.of(), null, Map.of(), Map.of(),
                null, null, null, null, Map.of(), Map.of(), null), null);
        assertEquals("OK", states(ok).get("ena"), "counters present and flat: measured, no limit hit");
    }

    @Test
    void pressureUsesTheMeasuredStallShareAndDoesNotDoubleReportMemory() {
        Inputs in = new Inputs(Map.of(),
                Map.of("cpu", new Inc(1080, 3600), "memory", new Inc(900, 3600), "io", new Inc(10, 3600)),
                Map.of("memory", new Inc(432, 3600), "io", new Inc(36, 3600)),
                null, Map.of(), Map.of(), null, null, null, null, Map.of(), Map.of(), null);
        XpHostLimits l = run(in, null);
        Finding cpu = only(l, "psi_cpu_some");
        assertEquals("WARNING", cpu.severity());
        assertEquals(0.30, cpu.evidence().get("stalled_share"), 1e-9);
        assertEquals("CRITICAL", only(l, "psi_memory_full").severity(), "12% of time with every task stalled on memory");
        assertTrue(l.findings().stream().noneMatch(x -> x.code().equals("psi_memory_some")), "\"some\" is not repeated when \"full\" fired");
        assertTrue(l.findings().stream().noneMatch(x -> x.code().startsWith("psi_io")), "1% io full is below the threshold");
        assertEquals("LIMITED", states(l).get("psi"));
    }

    @Test
    void stealOnAStandardBurstableInstanceIsReadAsCreditsAndOtherwiseAsContention() {
        Inputs in = new Inputs(Map.of(), Map.of(), Map.of(), 0.15, Map.of(), Map.of(), null, null, null, null, Map.of(), Map.of(), null);
        Finding burst = only(run(in, facts(true, "standard", "t3.medium")), "cpu_steal");
        assertEquals("AWS", burst.source());
        assertTrue(burst.detail().contains("credits"), burst.detail());
        Finding plain = only(run(in, facts(false, null, "m7i.large")), "cpu_steal");
        assertEquals("KERNEL", plain.source());
        assertFalse(plain.detail().contains("credits"));
        Finding unknown = only(run(in, null), "cpu_steal");
        assertFalse(unknown.detail().contains("credits"), "without facts the radar never guesses the instance is burstable");
        assertEquals("OK", states(run(new Inputs(Map.of(), Map.of(), Map.of(), 0.01, Map.of(), Map.of(), null, null, null, null,
                Map.of(), Map.of(), null), null)).get("steal"));
    }

    @Test
    void containerThrottlingNeedsEnoughPeriodsAndIsGradedByShare() {
        Inputs in = new Inputs(Map.of(), Map.of(), Map.of(), null,
                Map.of("api", new Inc(36000, 3600), "cron", new Inc(40, 3600), "nginx", new Inc(36000, 3600)),
                Map.of("api", new Inc(21600, 3600), "cron", new Inc(40, 3600), "nginx", new Inc(360, 3600)),
                null, null, null, null, Map.of(), Map.of(), null);
        XpHostLimits l = run(in, null);
        Finding api = only(l, "container_throttled");
        assertEquals("CRITICAL", api.severity(), "60% of periods throttled");
        assertTrue(api.title().contains("api"));
        assertEquals(0.6, api.evidence().get("throttled_ratio"), 1e-9);
        assertEquals("LIMITED", states(l).get("container_cpu"), "cron (too few periods) and nginx (1%) are not findings");
    }

    @Test
    void conntrackTableFillAndSpotNoticesComeFirst() {
        Inputs in = new Inputs(Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of(), 252_000.0, 262_144.0,
                new Latest(1, Map.of("action", "terminate", "time", "2026-11-12T10:42:00Z")),
                new Latest(1, Map.of("notice_time", "2026-11-12T10:30:00Z")), Map.of(), Map.of(), null);
        XpHostLimits l = run(in, null);
        assertEquals("CRITICAL", l.findings().get(0).severity(), "critical findings sort first");
        Finding spot = only(l, "spot_interruption");
        assertTrue(spot.title().contains("terminate") && spot.title().contains("10:42"), spot.title());
        assertEquals("WARNING", only(l, "spot_rebalance").severity());
        assertEquals("CRITICAL", only(l, "conntrack_table").severity(), "96% full");

        XpHostLimits calm = run(new Inputs(Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of(), 1000.0, 262_144.0,
                new Latest(0, Map.of()), new Latest(0, Map.of()), Map.of(), Map.of(), null), null);
        assertEquals("OK", states(calm).get("spot"), "a reported 0 is measured: no notice");
        assertEquals("OK", states(calm).get("conntrack_table"));
    }

    @Test
    void ebsSaysWhetherTheInstanceOrTheVolumeIsTheCap() {
        Instant fetched = Instant.parse("2026-11-12T10:55:00Z");
        Inputs in = new Inputs(Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of(), null, null, null, null,
                Map.of("ebs.instance_iops_exceeded_pct", 0.0, "ebs.instance_throughput_exceeded_pct", 25.0),
                Map.of("vol-1", Map.of("iops_exceeded_pct", 40.0, "throughput_exceeded_pct", 0.0),
                        "vol-2", Map.of("iops_exceeded_pct", 0.0, "throughput_exceeded_pct", 0.0)), fetched);
        XpHostLimits l = run(in, null);
        Finding inst = only(l, "ebs_instance_limit");
        assertTrue(inst.detail().contains("will not help"), "instance-side: a bigger volume is the wrong fix");
        assertTrue(inst.detail().contains("throughput"));
        Finding vol = only(l, "ebs_volume_limit");
        assertTrue(vol.title().contains("vol-1") && vol.title().contains("IOPS"), vol.title());
        XpHostLimits.Signal ebs = l.signals().stream().filter(s -> s.code().equals("ebs")).findFirst().orElseThrow();
        assertEquals("LIMITED", ebs.state());
        assertTrue(ebs.note().contains(fetched.toString()), "says how old the CloudWatch data is");
    }

    private static XpEc2Facts facts(boolean burstable, String credits, String type) {
        return new XpEc2Facts("i-1", "us-east-1", "web", "running", type, 2, 4096L, "x86_64", "Linux/UNIX", burstable, credits,
                "Up to 5 Gigabit", null, null, "us-east-1a", "vpc-1", "subnet-1", "10.0.0.1", null, "ami-1", "al2023",
                null, null, "on-demand", null, null, null, List.of(), true, true, false, Map.of(), List.of(),
                new XpEc2Facts.StatusChecks("ok", "ok"), List.of(), List.of(), Instant.EPOCH);
    }
}
