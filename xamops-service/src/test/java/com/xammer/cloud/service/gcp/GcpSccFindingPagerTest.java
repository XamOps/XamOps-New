package com.xammer.cloud.service.gcp;

import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GcpSccFindingPagerTest {

    private static List<GcpSecurityFinding> findings(int n, String severity) {
        List<GcpSecurityFinding> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            GcpSecurityFinding f = new GcpSecurityFinding();
            f.setSeverity(severity);
            out.add(f);
        }
        return out;
    }

    @Test
    void endlessCriticalStopsAtMaxPagesAndSkipsOtherTiers() throws Exception {
        List<String> filters = new ArrayList<>();
        List<GcpSecurityFinding> sink = new ArrayList<>();
        GcpSccFindingPager.Result r = GcpSccFindingPager.fetch("p",
                (filter, token, size) -> {
                    filters.add(filter);
                    return new GcpSccFindingPager.Page(findings(2, "CRITICAL"), "next");
                }, sink::add, 2, 3, 1000);
        assertEquals(3, r.pagesFetched());
        assertTrue(r.stoppedEarly());
        assertEquals(6, sink.size());
        assertTrue(filters.stream().allMatch(f -> f.equals(GcpSccFindingPager.filterFor("CRITICAL"))));
    }

    @Test
    void stopsAtMaxFindingsBeforeMaxPages() throws Exception {
        GcpSccFindingPager.Result r = GcpSccFindingPager.fetch("p",
                (filter, token, size) -> new GcpSccFindingPager.Page(findings(100, "CRITICAL"), "next"),
                f -> { }, 100, 50, 250);
        assertEquals(3, r.pagesFetched());
        assertEquals(300, r.fetched());
        assertTrue(r.stoppedEarly());
    }

    @Test
    void tiersAreRequestedInOrderWithExactFilters() throws Exception {
        List<String> filters = new ArrayList<>();
        GcpSccFindingPager.Result r = GcpSccFindingPager.fetch("p",
                (filter, token, size) -> {
                    filters.add(filter);
                    return new GcpSccFindingPager.Page(findings(1, "LOW"), "");
                }, f -> { }, 10, 20, 100);
        assertFalse(r.stoppedEarly());
        assertEquals(List.of(
                "state=\"ACTIVE\" AND severity=\"CRITICAL\"",
                "state=\"ACTIVE\" AND severity=\"HIGH\"",
                "state=\"ACTIVE\" AND severity=\"MEDIUM\"",
                "state=\"ACTIVE\" AND severity=\"LOW\"",
                "state=\"ACTIVE\" AND severity=\"SEVERITY_UNSPECIFIED\""), filters);
    }

    @Test
    void pageTokenIsPassedOnAndBlankMovesToNextTier() throws Exception {
        List<String> calls = new ArrayList<>();
        GcpSccFindingPager.fetch("p",
                (filter, token, size) -> {
                    calls.add(filter.contains("\"CRITICAL\"") ? "C:" + token : filter.contains("\"HIGH\"") ? "H:" + token : "x");
                    if (filter.contains("\"CRITICAL\"") && token == null) {
                        return new GcpSccFindingPager.Page(List.of(), "t1");
                    }
                    return new GcpSccFindingPager.Page(List.of(), null);
                }, f -> { }, 10, 20, 100);
        assertEquals("C:null", calls.get(0));
        assertEquals("C:t1", calls.get(1));
        assertEquals("H:null", calls.get(2));
    }

    @Test
    void fetcherExceptionPropagates() {
        assertThrows(IllegalStateException.class, () -> GcpSccFindingPager.fetch("p",
                (filter, token, size) -> { throw new IllegalStateException("boom"); },
                f -> { }, 10, 20, 100));
    }

    @Test
    void pageSizeIsClamped() throws Exception {
        List<Integer> sizes = new ArrayList<>();
        GcpSccFindingPager.fetch("p", (f, t, s) -> { sizes.add(s); return new GcpSccFindingPager.Page(List.of(), null); },
                x -> { }, 0, 1, 10);
        GcpSccFindingPager.fetch("p", (f, t, s) -> { sizes.add(s); return new GcpSccFindingPager.Page(List.of(), null); },
                x -> { }, 5000, 1, 10);
        assertEquals(List.of(1, 1000), sizes);
    }

    @Test
    void exactlyFullBudgetOnLastTierIsNotStoppedEarly() throws Exception {
        // 5 tiers, one page each = 5 pages = the cap; the last tier ends cleanly.
        GcpSccFindingPager.Result r = GcpSccFindingPager.fetch("p",
                (f, t, s) -> new GcpSccFindingPager.Page(findings(1, "LOW"), null),
                x -> { }, 10, 5, 100);
        assertEquals(5, r.pagesFetched());
        assertFalse(r.stoppedEarly());
    }

    @Test
    void collectorDigestUsesExactTotalsAndFlags() {
        GcpSecurityFindingCollector c = new GcpSecurityFindingCollector(10);
        findings(3, "HIGH").forEach(c::add);
        GcpSecurityDigest d = c.digest(3, true, false, Map.of("HIGH", 262000L, "CRITICAL", 5L));
        assertEquals(262005L, d.total());
        assertEquals(262000L, d.severityTotals().get("HIGH"));
        assertTrue(d.truncated());
        assertFalse(d.approximate());
        assertTrue(c.digest(3, true, true, null).approximate());
    }
}
