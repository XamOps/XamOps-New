package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.xampulse.dto.XpChange;
import com.xammer.cloud.xampulse.service.insight.ChangeIntelligenceService.Raw;
import com.xammer.cloud.xampulse.service.insight.ChangeIntelligenceService.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ChangeIntelligenceServiceTest {

    private static final long T = 1_794_300_000L - (1_794_300_000L % 60); // a change minute
    private static final Instant FROM = Instant.ofEpochSecond(T - 3 * 3600);
    private static final Instant TO = Instant.ofEpochSecond(T + 3 * 3600);

    private static Map<String, Object> span(String instance, String id, String name, String image, long first, long last) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("instance_id", instance);
        r.put("labels_key", "container.id=" + id + ",container.image.name=" + image + ",container.name=" + name);
        r.put("first_ts", first);
        r.put("last_ts", last);
        return r;
    }

    @Test
    void containerImageChangeIsADeploySameImageIsARecreateAndTheDataEdgeIsNotAStart() {
        Instant historyStart = FROM.minusSeconds(24 * 3600);
        List<Map<String, Object>> rows = List.of(
                // api: running since before the history began (not a start), then a new image at T.
                span("i-1", "aaaaaaaaaaaaaaaa", "api", "api:1", historyStart.getEpochSecond(), T - 60),
                span("i-1", "bbbbbbbbbbbbbbbb", "api", "api:2", T, TO.getEpochSecond() - 60),
                // worker: same image, new container at T+600 (recreate).
                span("i-1", "cccccccccccccccc", "worker", "w:7", historyStart.getEpochSecond(), T + 540),
                span("i-1", "dddddddddddddddd", "worker", "w:7", T + 600, TO.getEpochSecond() - 60),
                // cron: appears mid-window, disappears an hour later with nothing after it.
                span("i-1", "eeeeeeeeeeeeeeee", "cron", "c:1", T + 1200, T + 4800));
        List<Raw> out = ChangeIntelligenceService.containerSpans(rows, FROM, TO, historyStart);
        Map<String, Raw> byKind = new LinkedHashMap<>();
        out.forEach(r -> byKind.put(r.kind() + ":" + r.subject(), r));
        Raw deploy = byKind.get("DEPLOY:api");
        assertNotNull(deploy, String.valueOf(out));
        assertEquals(Instant.ofEpochSecond(T), deploy.ts());
        assertEquals("Container api deployed api:2", deploy.title());
        assertTrue(deploy.detail().contains("Was api:1"), deploy.detail());
        assertEquals("RECREATE", byKind.get("RECREATE:worker").kind());
        assertNotNull(byKind.get("CONTAINER_START:cron"));
        assertEquals(Instant.ofEpochSecond(T + 4860), byKind.get("CONTAINER_STOP:cron").ts(), "stopped the minute after its last data");
        assertNull(byKind.get("CONTAINER_START:api"), "a container already running when the data begins did not start then");
        assertNull(byKind.get("CONTAINER_STOP:api"), "a replaced container is a deploy, not a stop");
    }

    @Test
    void kernelCounterResetIsARebootAndASilenceWithoutResetIsADataGap() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("instance_id", "i-1", "t", T, "restarted", 1L, "gap_secs", 900L));
        rows.add(Map.of("instance_id", "i-1", "t", T + 7200, "restarted", 0L, "gap_secs", 1200L));
        rows.add(Map.of("instance_id", "i-1", "t", T + 7260, "restarted", 0L, "gap_secs", 60L)); // ordinary minute: nothing
        List<Raw> out = ChangeIntelligenceService.hostContinuity(rows);
        assertEquals(2, out.size(), String.valueOf(out));
        assertEquals("REBOOT", out.get(0).kind());
        assertTrue(out.get(0).detail().contains("15 minutes without data"));
        assertEquals("DATA_GAP", out.get(1).kind());
        assertEquals("Data resumed after 20 minutes without data", out.get(1).title());
    }

    @Test
    void packageLinesFromDpkgDnfAndYumBecomeOneChangePerHostAndMinute() {
        long ms = T * 1000;
        List<Map<String, Object>> rows = List.of(
                Map.of("instance_id", "i-1", "ts_ms", ms + 1000, "body", "2026-11-12 10:00:01 upgrade openssl:amd64 3.0.2-0ubuntu1.10 3.0.2-0ubuntu1.12"),
                Map.of("instance_id", "i-1", "ts_ms", ms + 3000, "body", "2026-11-12 10:00:03 install nginx:amd64 <none> 1.24.0-2ubuntu7"),
                Map.of("instance_id", "i-2", "ts_ms", ms + 1000, "body", "2026-11-12T10:05:01+0000 SUBDEBUG Upgrade: openssl-libs-1:3.0.8-1.amzn2023.0.12.x86_64"),
                Map.of("instance_id", "i-3", "ts_ms", ms + 600_000, "body", "Nov 12 10:06:01 Updated: openssl-libs-1:1.0.2k-24.amzn2.0.7.x86_64"),
                Map.of("instance_id", "i-3", "ts_ms", ms + 600_500, "body", "unrelated line"));
        List<Raw> out = ChangeIntelligenceService.packages(rows);
        assertEquals(3, out.size(), String.valueOf(out));
        Raw ubuntu = out.stream().filter(r -> r.instanceId().equals("i-1")).findFirst().orElseThrow();
        assertEquals("2 package changes", ubuntu.title());
        assertTrue(ubuntu.detail().contains("openssl 3.0.2-0ubuntu1.10 → 3.0.2-0ubuntu1.12"), ubuntu.detail());
        assertTrue(ubuntu.detail().contains("nginx 1.24.0-2ubuntu7 installed"), ubuntu.detail());
        assertEquals("Package change: openssl-libs-1:3.0.8-1.amzn2023.0.12.x86_64 (upgrade)",
                out.stream().filter(r -> r.instanceId().equals("i-2")).findFirst().orElseThrow().title());
        assertTrue(out.stream().anyMatch(r -> r.instanceId().equals("i-3") && r.title().contains("(updated)")));
    }

    private static Signal signal(String key, double floor, double before, double after, double jitter) {
        TreeMap<Long, Double> p = new TreeMap<>();
        for (int m = -30; m <= 30; m++) {
            if (m == 0) {
                continue;
            }
            double base = m < 0 ? before : after;
            p.put(T + m * 60L, base + (Math.floorMod(m, 3) - 1) * jitter); // -1, 0, +1 around the base: median = base
        }
        return new Signal(key, key, "%", floor, p);
    }

    private static Raw raw(String kind) {
        return new Raw(Instant.ofEpochSecond(T), "i-1", kind, "t", "d", "AGENT", null);
    }

    @Test
    void aStrongShiftAfterTheChangeIsReportedWithBeforeAndAfter() {
        XpChange c = ChangeIntelligenceService.judge(raw("DEPLOY"),
                List.of(signal("cpu", 5, 30, 75, 2), signal("memory", 3, 50, 51, 1)), TO, false);
        assertEquals("FOLLOWED_BY_CHANGE", c.verdict());
        assertEquals(1, c.effects().size(), "memory moved 1 point: not reported");
        XpChange.Effect cpu = c.effects().get(0);
        assertEquals("STRONG", cpu.strength());
        assertEquals(30, cpu.before(), 1e-9);
        assertEquals(75, cpu.after(), 1e-9);
        assertEquals(1.5, cpu.relative(), 1e-9);
    }

    @Test
    void noisySignalsAndSmallMovesAreNotCalledEffects() {
        // CPU swings +-20 around 40 and settles at 48: within its normal spread.
        XpChange c = ChangeIntelligenceService.judge(raw("PACKAGE"), List.of(signal("cpu", 5, 40, 48, 20)), TO, false);
        assertEquals("NO_CLEAR_EFFECT", c.verdict());
        assertTrue(c.effects().isEmpty());
    }

    @Test
    void errorsAreZeroFilledSoAQuietBeforeAndANoisyAfterIsSeen() {
        TreeMap<Long, Double> errors = new TreeMap<>();
        errors.put(T - 1800L, 0.0); // the log volume table has rows only when there are logs
        for (int m = 1; m <= 30; m++) {
            errors.put(T + m * 60L, 12.0);
        }
        XpChange c = ChangeIntelligenceService.judge(raw("DEPLOY"), List.of(new Signal("errors", "Errors per minute", "/min", 1, errors)), TO, false);
        assertEquals("FOLLOWED_BY_CHANGE", c.verdict());
        assertEquals(0, c.effects().get(0).before(), 1e-9);
        assertEquals(12, c.effects().get(0).after(), 1e-9);
    }

    @Test
    void tooRecentAndTooLittleDataAreSaidPlainly() {
        assertEquals("TOO_RECENT", ChangeIntelligenceService.judge(raw("DEPLOY"), List.of(signal("cpu", 5, 30, 75, 2)),
                Instant.ofEpochSecond(T + 120), false).verdict());
        TreeMap<Long, Double> sparse = new TreeMap<>(Map.of(T - 60, 10.0, T + 60, 90.0));
        assertEquals("NOT_ENOUGH_DATA", ChangeIntelligenceService.judge(raw("DEPLOY"),
                List.of(new Signal("cpu", "CPU", "%", 5, sparse)), TO, false).verdict());
    }

    @Test
    void changesWithinTenMinutesOnOneHostAreFlaggedAsIndistinguishable() {
        Raw a = new Raw(Instant.ofEpochSecond(T), "i-1", "DEPLOY", "a", "", "AGENT", "api");
        Raw b = new Raw(Instant.ofEpochSecond(T + 300), "i-1", "PACKAGE", "b", "", "LOGS", null);
        Raw c = new Raw(Instant.ofEpochSecond(T + 300), "i-2", "PACKAGE", "c", "", "LOGS", null);
        Raw d = new Raw(Instant.ofEpochSecond(T + 3600), "i-1", "REBOOT", "d", "", "AGENT", null);
        List<Raw> all = List.of(a, b, c, d);
        assertTrue(ChangeIntelligenceService.overlapping(a, all));
        assertFalse(ChangeIntelligenceService.overlapping(c, all), "another host's change does not blur this one");
        assertFalse(ChangeIntelligenceService.overlapping(d, all));
    }
}
