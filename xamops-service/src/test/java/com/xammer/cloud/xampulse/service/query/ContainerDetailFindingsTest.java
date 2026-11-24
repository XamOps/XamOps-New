package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.dto.XpHostLimits.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ContainerDetailFindingsTest {

    private static final long STEP = 60_000;
    private static final double LIMIT = 2.0 * 1024 * 1024 * 1024;

    private static TreeMap<Long, Double> memory(double... shares) {
        TreeMap<Long, Double> m = new TreeMap<>();
        for (int i = 0; i < shares.length; i++) {
            m.put(i * STEP, shares[i] * LIMIT);
        }
        return m;
    }

    private static TreeMap<Long, Double> limit() {
        TreeMap<Long, Double> l = new TreeMap<>();
        l.put(0L, LIMIT);
        return l;
    }

    private static List<String> codes(List<Finding> f) {
        return f.stream().map(Finding::code).toList();
    }

    @Test
    void aRestartRightAfterMemoryHitTheLimitIsALikelyOomKill() {
        // Memory climbs to 97% of the limit at minute 4, Docker restarts the container at minute 5.
        List<Finding> f = ContainerDetailService.findings("api", 0, 0, memory(0.5, 0.7, 0.85, 0.93, 0.97, 0.30), limit(), List.of(300L), 1, STEP);
        assertEquals(List.of("container_oom"), codes(f));
        assertTrue(f.get(0).title().contains("likely"), "Docker does not say why a container exited: never claimed as certain");
    }

    @Test
    void aRestartWithMemoryWellBelowTheLimitIsNotCalledAnOom() {
        List<Finding> f = ContainerDetailService.findings("api", 0, 0, memory(0.4, 0.4, 0.4, 0.4, 0.4, 0.4), limit(), List.of(300L), 1, STEP);
        assertTrue(f.isEmpty(), String.valueOf(f));
    }

    @Test
    void memoryNearTheLimitWithoutARestartIsAWarning() {
        List<Finding> f = ContainerDetailService.findings("api", 0, 0, memory(0.5, 0.92, 0.6), limit(), List.of(), 0, STEP);
        assertEquals(List.of("container_memory_near_limit"), codes(f));
        assertEquals(0.92, f.get(0).evidence().get("peak_share"), 1e-9);
    }

    @Test
    void withNoLimitSetTheMemoryFindingsSpeakAboutTheHostAndSayToSetALimit() {
        // Docker reports the host's memory as the "limit" when none is set: "raise the limit" would be wrong advice.
        Finding near = ContainerDetailService.findings("api", 0, 0, memory(0.5, 0.92, 0.6), limit(), List.of(), 0, STEP, true).get(0);
        assertEquals("container_memory_near_limit", near.code());
        assertTrue(near.title().contains("host's memory") && near.title().contains("no limit"), near.title());
        assertTrue(near.fix().startsWith("Set a memory limit"), near.fix());
        Finding oom = ContainerDetailService.findings("api", 0, 0, memory(0.5, 0.7, 0.85, 0.93, 0.97, 0.30), limit(), List.of(300L), 1, STEP, true).get(0);
        assertEquals("container_oom", oom.code());
        assertTrue(oom.title().contains("host ran out of memory") && oom.title().contains("likely"), oom.title());
        assertTrue(oom.fix().startsWith("Set a memory limit"), oom.fix());
    }

    @Test
    void throttlingNeedsEnoughPeriodsAndRestartLoopsAreCounted() {
        assertEquals(List.of("container_throttled"), codes(ContainerDetailService.findings("api", 1000, 600, memory(0.2), limit(), List.of(), 0, STEP)));
        assertTrue(ContainerDetailService.findings("api", 50, 50, memory(0.2), limit(), List.of(), 0, STEP).isEmpty(), "50 periods is too few to judge");
        assertEquals(List.of("container_restart_loop"), codes(ContainerDetailService.findings("api", 0, 0, memory(0.2), limit(), List.of(60L, 600L, 1200L), 3, STEP)));
    }

    @Test
    void containerNamesAreValidatedLikeDockerNames() {
        assertTrue(ContainerDetailService.NAME.matcher("api").matches());
        assertTrue(ContainerDetailService.NAME.matcher("my_app.web-1").matches());
        assertFalse(ContainerDetailService.NAME.matcher("/api").matches());
        assertFalse(ContainerDetailService.NAME.matcher("a'b").matches());
        assertFalse(ContainerDetailService.NAME.matcher("").matches());
    }
}
