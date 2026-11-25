package com.xammer.cloud.xampulse.service.aws;

import com.xammer.cloud.xampulse.dto.XpEc2Facts;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class Ec2AwsViewTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private static XpEc2Facts facts(String system, String instance, boolean burstable, String credits, boolean imdsV2,
                                    List<XpEc2Facts.Volume> vols, List<XpEc2Facts.ScheduledEvent> events, Instant amiCreated,
                                    String lifecycle, String publicIp, String profile) {
        return new XpEc2Facts("i-0123456789abcdef0", "ap-south-1", "web", "running", "t3.medium", 2, 4096L, "x86_64",
                "Linux/UNIX", burstable, credits, "Up to 5 Gigabit", 1000, 43.75, "ap-south-1a", "vpc-1", "subnet-1",
                "10.0.0.1", publicIp, "ami-1", "al2023", amiCreated, NOW.minus(Duration.ofDays(3)), lifecycle, null, null,
                profile, List.of("web (sg-1)"), imdsV2, true, false, Map.of("Name", "web"), vols,
                new XpEc2Facts.StatusChecks(system, instance), events, List.of(), NOW);
    }

    private static XpEc2Facts.Volume vol(String id, String type, boolean encrypted) {
        return new XpEc2Facts.Volume(id, "/dev/xvda", type, 30, 100, null, encrypted, true);
    }

    private static List<String> titles(XpEc2Facts f) {
        return Ec2FactsService.withFindings(f, NOW).findings().stream().map(XpEc2Facts.Finding::title).toList();
    }

    @Test
    void aHealthyHardenedInstanceOnlyGetsTheMonitoringNote() {
        XpEc2Facts f = facts("ok", "ok", false, null, true, List.of(vol("vol-1", "gp3", true)), List.of(),
                NOW.minus(Duration.ofDays(30)), "on-demand", null, "arn:aws:iam::1:instance-profile/p");
        assertEquals(List.of("Basic CloudWatch monitoring"), titles(f));
    }

    @Test
    void failingChecksAndScheduledEventsComeFirstAndSayWhatToDo() {
        XpEc2Facts f = facts("impaired", "impaired", false, null, true, List.of(), List.of(
                        new XpEc2Facts.ScheduledEvent("instance-retirement", "The instance is running on degraded hardware", NOW.plus(Duration.ofDays(5)), null)),
                null, "on-demand", null, "p");
        List<XpEc2Facts.Finding> out = Ec2FactsService.withFindings(f, NOW).findings();
        assertEquals("CRITICAL", out.get(0).severity());
        assertEquals("CRITICAL", out.get(1).severity());
        assertTrue(out.get(0).detail().contains("Stopping and starting"), "system check: stop/start, not reboot");
        XpEc2Facts.Finding retire = out.stream().filter(x -> x.title().startsWith("Scheduled instance retirement")).findFirst().orElseThrow();
        assertEquals("WARNING", retire.severity());
        assertTrue(retire.detail().contains("Stop and start"));
    }

    @Test
    void securityCostAndRiskObservations() {
        XpEc2Facts f = facts("ok", "ok", true, "standard", false,
                List.of(vol("vol-1", "gp2", false), vol("vol-2", "gp2", true)), List.of(), NOW.minus(Duration.ofDays(500)),
                "spot", "13.1.2.3", null);
        List<String> t = titles(f);
        assertTrue(t.contains("IMDSv1 is allowed"));
        assertTrue(t.contains("No IAM instance profile"));
        assertTrue(t.contains("2 gp2 volumes"));
        assertTrue(t.contains("1 unencrypted EBS volume"));
        assertTrue(t.contains("AMI is 500 days old"));
        assertTrue(t.contains("Spot instance"));
        assertTrue(t.contains("Burstable instance in standard mode"));
        assertTrue(t.contains("Public IPv4 address 13.1.2.3"));
    }

    @Test
    void periodTargetsAboutThreeHundredSixtyPointsButNeverFinerThanPublished() {
        assertEquals(300, CloudWatchViewService.period(Duration.ofHours(1), false));
        assertEquals(60, CloudWatchViewService.period(Duration.ofHours(1), true));
        assertEquals(300, CloudWatchViewService.period(Duration.ofHours(24), false));
        assertEquals(1800, CloudWatchViewService.period(Duration.ofDays(7), false));
        assertEquals(0, CloudWatchViewService.period(Duration.ofDays(7), true) % 60);
    }

    /** In-memory stand-in for the Redis store that two backend replicas share. */
    static final class SharedStore implements com.xammer.cloud.xampulse.service.stream.TicketStore {
        final Map<String, String> m = new java.util.concurrent.ConcurrentHashMap<>();
        boolean down;
        public void put(String k, String v, Duration ttl) { check(); m.put(k, v); }
        public java.util.Optional<String> getAndDelete(String k) { check(); return java.util.Optional.ofNullable(m.remove(k)); }
        public java.util.Optional<String> get(String k) { check(); return java.util.Optional.ofNullable(m.get(k)); }
        public boolean putIfAbsent(String k, String v, Duration ttl) { check(); return m.putIfAbsent(k, v) == null; }
        private void check() { if (down) throw new IllegalStateException("redis down"); }
    }

    @Test
    void oneBilledRefreshPerInstancePerMinuteAcrossReplicasEvenWhenRedisIsDown() {
        SharedStore redis = new SharedStore();
        CloudWatchViewService replicaA = new CloudWatchViewService(null, null, null, null, redis, java.time.Clock.systemUTC());
        CloudWatchViewService replicaB = new CloudWatchViewService(null, null, null, null, redis, java.time.Clock.systemUTC());
        assertTrue(replicaA.acquireRefresh("7|i-1", NOW), "first refresh goes through");
        assertFalse(replicaB.acquireRefresh("7|i-1", NOW), "the other replica must not fetch (and bill) again");
        assertTrue(replicaB.acquireRefresh("7|i-2", NOW), "another instance is independent");
        replicaA.releaseRefresh("7|i-1");
        assertTrue(replicaB.acquireRefresh("7|i-1", NOW), "a failed fetch releases the gate so the user can retry");

        redis.down = true;
        assertTrue(replicaA.acquireRefresh("7|i-9", NOW), "Redis down: the local gate still lets the first one through");
        assertFalse(replicaA.acquireRefresh("7|i-9", NOW.plusSeconds(10)), "and still limits that replica to one a minute");
        assertTrue(replicaA.acquireRefresh("7|i-9", NOW.plusSeconds(61)));
    }

    @Test
    void completedAndCanceledScheduledEventsAreNotUpcoming() {
        assertTrue(Ec2FactsService.isFinishedEvent("[Completed] The instance is running on degraded hardware"));
        assertTrue(Ec2FactsService.isFinishedEvent("[Canceled] scheduled reboot"));
        assertFalse(Ec2FactsService.isFinishedEvent("The instance is scheduled for retirement"));
        assertFalse(Ec2FactsService.isFinishedEvent(null));
    }

    @Test
    void periodFollowsCloudWatchRetentionForOldWindowsSoAFetchNeverComesBackEmpty() {
        // A 1-day window that starts 20 days ago with detailed monitoring: 86400/360 = 240 s would return nothing.
        assertEquals(300, CloudWatchViewService.period(Duration.ofDays(1), true, Duration.ofDays(20)));
        assertEquals(0, CloudWatchViewService.period(Duration.ofDays(3), true, Duration.ofDays(30)) % 300);
        assertEquals(3600, CloudWatchViewService.period(Duration.ofDays(1), true, Duration.ofDays(70)));
        assertEquals(60, CloudWatchViewService.period(Duration.ofHours(1), true, Duration.ofDays(2)), "recent data keeps 1-minute detail");
        assertEquals(240, CloudWatchViewService.period(Duration.ofDays(1), true, Duration.ofDays(14)), "under 15 days, multiples of 60 are fine");
    }

    @Test
    void creditsOnlyForBurstableAndBurstBalanceOnlyForGp2St1Sc1() {
        XpEc2Facts steady = facts("ok", "ok", false, null, true, List.of(vol("vol-1", "gp3", true)), List.of(), null, "on-demand", null, "p");
        List<CloudWatchViewService.Spec> s = CloudWatchViewService.specs(steady);
        assertTrue(s.stream().noneMatch(x -> x.name().startsWith("CPUCredit")));
        assertTrue(s.stream().noneMatch(x -> x.name().equals("BurstBalance")));
        assertEquals(11 + 10, s.size());
        // The instance-side and volume-side "exceeded" checks are what tell "raise the volume" from "bigger instance".
        assertTrue(s.stream().anyMatch(x -> x.name().equals("InstanceEBSIOPSExceededCheck") && x.volumeId() == null && "AWS/EC2".equals(x.namespace())));
        assertTrue(s.stream().anyMatch(x -> x.name().equals("VolumeThroughputExceededCheck") && "vol-1".equals(x.volumeId()) && "AWS/EBS".equals(x.namespace())));

        XpEc2Facts burst = facts("ok", "ok", true, "standard", true, List.of(vol("vol-1", "gp2", true)), List.of(), null, "on-demand", null, "p");
        List<CloudWatchViewService.Spec> b = CloudWatchViewService.specs(burst);
        assertTrue(b.stream().anyMatch(x -> x.name().equals("CPUCreditBalance")));
        assertTrue(b.stream().anyMatch(x -> x.name().equals("BurstBalance") && "vol-1".equals(x.volumeId())));
    }

    @Test
    void rawStatisticsBecomeOperatorUnits() {
        int period = 300;
        long t = NOW.toEpochMilli();
        Map<CloudWatchViewService.Spec, TreeMap<Long, Double>> raw = Map.of(
                new CloudWatchViewService.Spec("AWS/EC2", "NetworkIn", "Sum", null), new TreeMap<>(Map.of(t, 3_000_000.0)),
                new CloudWatchViewService.Spec("AWS/EC2", "CPUCreditBalance", "Average", null), new TreeMap<>(Map.of(t, 42.0)),
                new CloudWatchViewService.Spec("AWS/EBS", "VolumeReadOps", "Sum", "vol-1"), new TreeMap<>(Map.of(t, 6000.0)),
                new CloudWatchViewService.Spec("AWS/EBS", "VolumeTotalReadTime", "Sum", "vol-1"), new TreeMap<>(Map.of(t, 12.0)),
                new CloudWatchViewService.Spec("AWS/EBS", "VolumeReadBytes", "Sum", "vol-1"), new TreeMap<>(Map.of(t, 300.0 * 1048576)),
                new CloudWatchViewService.Spec("AWS/EBS", "VolumeIdleTime", "Sum", "vol-1"), new TreeMap<>(Map.of(t, 150.0)));
        CloudWatchViewService.Derived d = CloudWatchViewService.derive(raw, period);
        assertEquals(10_000.0, d.instance().get("net.in_bps").get(0)[1], 1e-9, "bytes per period -> bytes/s");
        assertEquals(42.0, d.instance().get("cpu.credit_balance").get(0)[1], 1e-9);
        Map<String, java.util.List<double[]>> v = d.volumes().get("vol-1");
        assertEquals(20.0, v.get("iops.read").get(0)[1], 1e-9, "ops per period -> IOPS");
        assertEquals(2.0, v.get("latency_ms.read").get(0)[1], 1e-9, "12 s over 6000 ops -> 2 ms");
        assertEquals(1.0, v.get("mibps.read").get(0)[1], 1e-9);
        assertEquals(50.0, v.get("idle_pct").get(0)[1], 1e-9);
        assertNull(v.get("latency_ms.write"), "no write ops -> no write latency series");
    }
}
