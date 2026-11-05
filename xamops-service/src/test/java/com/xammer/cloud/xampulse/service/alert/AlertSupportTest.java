package com.xammer.cloud.xampulse.service.alert;

import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.alert.channel.ChannelConfig;
import com.xammer.cloud.xampulse.service.alert.channel.Notification;
import com.xammer.cloud.xampulse.service.alert.channel.NotificationChannel;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertSupportTest {

    // ---------------- rule compiler ----------------

    private final RuleCompiler compiler = new RuleCompiler();
    private final Instant now = Instant.parse("2026-02-01T10:00:00Z");

    private XpAlertRule rule(XpCondition c) {
        return new XpAlertRule(null, null, "High CPU", true, "HIGH", new XpAlertScope(List.of(7L), List.of("i-0abc1234"), null), c,
                new XpGrouping(List.of("instance_id"), "15m"), new XpRouting(List.of(1L), false, "https://runbooks.example.com/cpu"));
    }

    private XpCondition metric() {
        return new XpCondition("METRIC_THRESHOLD", "system.cpu.utilization", List.of(new XpMetricFilter("state", "=", "idle")), null, "avg",
                "5m", "<", 0.1, "5m", "KEEP", null);
    }

    @Test
    void everyConditionTypeValidatesAndCompilesToATenantScopedStatement() {
        List<XpCondition> all = List.of(
                metric(),
                new XpCondition("LOG_COUNT", null, null, "severity:error service:api", null, "10m", ">", 50.0, "0m", "OK", null),
                new XpCondition("HEARTBEAT_MISSING", null, null, null, null, "5m", null, null, "0m", "ALERT", null),
                new XpCondition("METRIC_ANOMALY", "system.cpu.load_average.1m", null, null, null, "15m", null, null, "5m", "KEEP", null),
                new XpCondition("FORECAST", "system.filesystem.utilization", null, null, null, "1h", null, null, "0m", "KEEP", 14));
        for (XpCondition c : all) {
            XpAlertRule r = rule(c);
            compiler.validate(r);
            RuleCompiler.Compiled out = compiler.compile(7L, r, now);
            assertTrue(out.sql().sql().contains("WHERE tenant_id IN (?)"), c.type());
            assertEquals(List.of(7L), out.sql().tenantIds());
            assertEquals(7L, out.sql().params().get(0));
            assertFalse(out.sql().sql().contains("i-0abc1234"), "instance ids are parameters");
        }
    }

    @Test
    void maliciousMetricFilterAndWindowValuesAreRejected() {
        XpCondition badMetric = new XpCondition("METRIC_THRESHOLD", "cpu'; DROP TABLE x;--", null, null, "avg", "5m", ">", 1.0, "0m", "KEEP", null);
        assertEquals("XP_INVALID_REQUEST", assertThrows(XamPulseException.class, () -> compiler.validate(rule(badMetric))).getCode());
        XpCondition badFilter = new XpCondition("LOG_COUNT", null, null, "tenant_id:1", null, "5m", ">", 1.0, "0m", "KEEP", null);
        assertEquals("XP_BAD_FILTER", assertThrows(XamPulseException.class, () -> compiler.validate(rule(badFilter))).getCode());
        XpCondition badAgg = new XpCondition("METRIC_THRESHOLD", "a.b", null, null, "avg) FROM secrets --", "5m", ">", 1.0, "0m", "KEEP", null);
        assertThrows(XamPulseException.class, () -> compiler.validate(rule(badAgg)));
        XpCondition badWindow = new XpCondition("METRIC_THRESHOLD", "a.b", null, null, "avg", "25h", ">", 1.0, "0m", "KEEP", null);
        assertThrows(XamPulseException.class, () -> compiler.validate(rule(badWindow)));
        XpCondition badFor = new XpCondition("METRIC_THRESHOLD", "a.b", null, null, "avg", "5m", ">", 1.0, "61m", "KEEP", null);
        assertThrows(XamPulseException.class, () -> compiler.validate(rule(badFor)));
        XpCondition badLabel = new XpCondition("METRIC_THRESHOLD", "a.b", List.of(new XpMetricFilter("k'x", "=", "v")), null, "avg", "5m", ">", 1.0, "0m", "KEEP", null);
        assertThrows(XamPulseException.class, () -> compiler.validate(rule(badLabel)));
    }

    @Test
    void ruleScopeAndRoutingRules() {
        XpCondition c = metric();
        assertThrows(XamPulseException.class, () -> compiler.validate(new XpAlertRule(null, null, "x", true, "HIGH",
                new XpAlertScope(List.of(1L, 2L), null, null), c, null, null)));
        assertThrows(XamPulseException.class, () -> compiler.validate(new XpAlertRule(null, null, "x", true, "HIGH",
                new XpAlertScope(List.of(1L), null, "env=prod"), c, null, null)));
        assertThrows(XamPulseException.class, () -> compiler.validate(new XpAlertRule(null, null, "x", true, "HIGH",
                new XpAlertScope(List.of(1L), null, null), c, null, new XpRouting(List.of(), true, null))));
        assertThrows(XamPulseException.class, () -> compiler.validate(new XpAlertRule(null, null, "x", true, "HIGH",
                new XpAlertScope(List.of(1L), null, null), c, null, new XpRouting(List.of(), false, "javascript:alert(1)"))));
        assertThrows(XamPulseException.class, () -> compiler.validate(new XpAlertRule(null, null, "x", true, "EXTREME",
                new XpAlertScope(List.of(1L), null, null), c, null, null)));
    }

    // ---------------- outbound URL guard ----------------

    private OutboundUrlGuard guardResolving(String... ips) {
        return new OutboundUrlGuard(host -> {
            List<InetAddress> out = new ArrayList<>();
            try {
                for (String ip : ips) {
                    out.add(InetAddress.getByName(ip));
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return out;
        });
    }

    @Test
    void guardRejectsPrivateLoopbackLinkLocalAndMetadataTargets() {
        String[] blocked = {"127.0.0.1", "10.1.2.3", "172.16.5.5", "192.168.1.1", "169.254.169.254", "0.0.0.0", "100.64.0.1",
                "198.18.0.1", "::1", "fe80::1", "fd00:ec2::254", "fc00::1", "224.0.0.1", "255.255.255.255", "::ffff:10.0.0.1"};
        for (String ip : blocked) {
            XamPulseException e = assertThrows(XamPulseException.class, () -> guardResolving(ip).validate("https://hooks.example.com/x"), ip);
            assertEquals("XP_INVALID_REQUEST", e.getCode());
        }
    }

    @Test
    void guardAllowsPublicTargetsAndBlocksRebindingToAnyPrivateAnswer() {
        assertEquals("hooks.example.com", guardResolving("93.184.216.34").validate("https://hooks.example.com/x").getHost());
        // DNS answers with one public and one private address: rejected.
        assertThrows(XamPulseException.class, () -> guardResolving("93.184.216.34", "10.0.0.5").validate("https://rebind.example.com/x"));
    }

    @Test
    void guardRejectsNonHttpsCredentialsAndUnresolvableHosts() {
        OutboundUrlGuard g = guardResolving("93.184.216.34");
        assertThrows(XamPulseException.class, () -> g.validate("http://hooks.example.com/x"));
        assertThrows(XamPulseException.class, () -> g.validate("ftp://hooks.example.com/x"));
        assertThrows(XamPulseException.class, () -> g.validate("https://user:pw@hooks.example.com/x"));
        assertThrows(XamPulseException.class, () -> g.validate("https:///nohost"));
        assertThrows(XamPulseException.class, () -> g.validate("not a url"));
        assertThrows(XamPulseException.class, () -> g.validate(null));
        assertThrows(XamPulseException.class, () -> new OutboundUrlGuard(h -> List.of()).validate("https://nx.example.com/"));
        assertThrows(XamPulseException.class, () -> g.validate("https://localhost-alias.example.com/" + "a".repeat(2100)));
    }

    // ---------------- notification router ----------------

    private final class Recorder implements NotificationChannel {
        final List<Notification> sent = new ArrayList<>();

        @Override public String kind() { return "WEBHOOK"; }

        @Override public void send(ChannelConfig config, Notification n) { sent.add(n); }
    }

    private Notification fired(String instance) {
        return new Notification("FIRED", "High CPU", "text", "HIGH", 7L, instance, 1L, "/xampulse/alerts?incident=1", now);
    }

    private NotificationRouter router(Recorder rec) {
        ChannelService channels = mock(ChannelService.class);
        when(channels.kindOf(eq(1L), eq(7L))).thenReturn("WEBHOOK");
        when(channels.load(eq(1L), eq(7L))).thenReturn(new ChannelConfig(null, "https://hooks.example.com/x"));
        when(channels.sender("WEBHOOK")).thenReturn(rec);
        return new NotificationRouter(channels);
    }

    @Test
    void silenceSuppressesDeliveryButNotTheDecisionRecord() {
        Recorder rec = new Recorder();
        var res = router(rec).route(7L, 5L, "i-1", Duration.ofMinutes(15), List.of(1L), fired("i-1"), true, now);
        assertEquals(NotificationRouter.Status.SUPPRESSED_SILENCED, res.status());
        assertTrue(rec.sent.isEmpty());
    }

    @Test
    void repeatsInsideTheDedupeWindowAreDroppedButResolveAlwaysGoesOut() {
        Recorder rec = new Recorder();
        NotificationRouter r = router(rec);
        assertEquals(NotificationRouter.Status.SENT, r.route(7L, 5L, "i-1", Duration.ofMinutes(15), List.of(1L), fired("i-1"), false, now).status());
        assertEquals(NotificationRouter.Status.SUPPRESSED_DEDUPE,
                r.route(7L, 5L, "i-1", Duration.ofMinutes(15), List.of(1L), fired("i-1"), false, now.plusSeconds(300)).status());
        assertEquals(NotificationRouter.Status.SENT,
                r.route(7L, 5L, "i-2", Duration.ofMinutes(15), List.of(1L), fired("i-2"), false, now.plusSeconds(300)).status(), "different group");
        assertEquals(NotificationRouter.Status.SENT,
                r.route(7L, 5L, "i-1", Duration.ofMinutes(15), List.of(1L), fired("i-1"), false, now.plusSeconds(1000)).status(), "window elapsed");
        Notification resolved = new Notification("RESOLVED", "High CPU", "ok", "HIGH", 7L, "i-1", 1L, null, now);
        assertEquals(NotificationRouter.Status.SENT, r.route(7L, 5L, "i-1", Duration.ofMinutes(15), List.of(1L), resolved, false, now.plusSeconds(1001)).status());
        assertEquals(4, rec.sent.size());
    }

    @Test
    void demoDataNeverReachesExternalChannelsAndMissingChannelsAreReported() {
        Recorder rec = new Recorder();
        NotificationRouter r = router(rec);
        assertEquals(NotificationRouter.Status.SKIPPED_DEMO,
                r.route(7L, 5L, "i-demo-1", Duration.ofMinutes(15), List.of(1L), fired("i-demo-1"), false, now).status());
        assertEquals(NotificationRouter.Status.NO_CHANNELS, r.route(7L, 5L, "i-1", Duration.ofMinutes(15), List.of(), fired("i-1"), false, now).status());
        assertEquals(NotificationRouter.Status.FAILED, r.route(7L, 5L, "i-9", Duration.ofMinutes(15), List.of(99L), fired("i-9"), false, now).status());
        assertTrue(rec.sent.isEmpty());
    }

    // ---------------- incident correlation ----------------

    @Test
    void incidentsGroupBySharedInstanceThenByTenMinuteWindow() {
        IncidentCorrelator c = new IncidentCorrelator();
        var a = new IncidentCorrelator.OpenIncident(1, List.of("i-1"), now.minus(Duration.ofHours(2)));
        var b = new IncidentCorrelator.OpenIncident(2, List.of("i-2"), now.minus(Duration.ofMinutes(5)));
        assertEquals(1, c.pick(List.of(b, a), "i-1", now).orElseThrow().id(), "same instance beats recency");
        assertEquals(2, c.pick(List.of(a, b), "i-3", now).orElseThrow().id(), "recent activity joins");
        assertTrue(c.pick(List.of(a), "i-3", now).isEmpty(), "old unrelated incident is not joined");
    }

    // ---------------- preview ----------------

    @Test
    void previewReportsOnlyBreachesThatLastedLongEnoughAndCountsNotifications() {
        List<AlertPreviewEngine.Point> pts = new ArrayList<>();
        double[] v = {1, 9, 9, 9, 9, 1, 9, 1, 9, 9, 9};
        for (int i = 0; i < v.length; i++) {
            pts.add(new AlertPreviewEngine.Point(now.plus(Duration.ofMinutes(i)), v[i]));
        }
        XpAlertPreview p = AlertPreviewEngine.run(Map.of("i-1", pts), ">", 5, Duration.ofMinutes(2));
        assertEquals(11, p.evaluatedPoints());
        assertEquals(2, p.firings().size(), "the one-point blip at minute 6 is held back by 'for'");
        assertEquals(now.plus(Duration.ofMinutes(1)), p.firings().get(0).from());
        assertEquals(now.plus(Duration.ofMinutes(5)), p.firings().get(0).to());
        assertEquals(9, p.firings().get(0).peak());
        assertEquals(2, p.wouldNotify());
        assertEquals(0, AlertPreviewEngine.run(Map.of("i-1", pts), ">", 50, Duration.ZERO).firings().size());
    }

    // ---------------- silence matching & channel masking ----------------

    @Test
    void emptySilenceMatcherMatchesNothing() {
        assertFalse(SilenceService.matches(new XpSilenceMatcher(null, null, null), 1L, "i-1", "HIGH"));
        assertFalse(SilenceService.matches(new XpSilenceMatcher(List.of(), List.of(), List.of()), 1L, "i-1", "HIGH"));
        assertTrue(SilenceService.matches(new XpSilenceMatcher(List.of("i-1"), null, null), 1L, "i-1", "HIGH"));
        assertFalse(SilenceService.matches(new XpSilenceMatcher(List.of("i-1"), null, null), 1L, "i-2", "HIGH"));
        assertTrue(SilenceService.matches(new XpSilenceMatcher(List.of("i-1"), List.of(5L), List.of("HIGH")), 5L, "i-1", "HIGH"));
        assertFalse(SilenceService.matches(new XpSilenceMatcher(List.of("i-1"), List.of(5L), List.of("LOW")), 5L, "i-1", "HIGH"));
    }

    @Test
    void channelSummariesMaskTheDestination() {
        assertEquals("hooks.slack.com/…/••••", ChannelService.mask("SLACK", new ChannelConfig(null, "https://hooks.slack.com/services/T0/B0/SECRETSECRET")));
        assertEquals("a***@example.com (+1)", ChannelService.mask("EMAIL", new ChannelConfig(List.of("alice@example.com", "bob@example.com"), null)));
        assertFalse(new ChannelConfig(null, "https://x/secret").toString().contains("secret"));
    }

    @Test
    void durationSpecAcceptsOnlyMinutesAndHours() {
        assertEquals(Duration.ofMinutes(5), DurationSpec.parse("5m", "w"));
        assertEquals(Duration.ofHours(24), DurationSpec.parse("24h", "w"));
        for (String bad : new String[] {"5", "5s", "m", "-5m", "5 m", "99999m", "", "1d"}) {
            assertThrows(XamPulseException.class, () -> DurationSpec.parse(bad, "w"), bad);
        }
    }
}
