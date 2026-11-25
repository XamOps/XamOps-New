package com.xammer.cloud.service.gcp;

import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Consumer;

/**
 * Bounded, severity-tiered paging over SCC ListFindings. No network types here, so it is unit-testable.
 *
 * <p>
 * SCC's {@code order_by} cannot sort by severity (only name, parent, state, category, resource_name,
 * event_time, security_marks.marks), so "most severe first" is done by querying one severity tier at a
 * time, CRITICAL first. All tiers share one page budget and one finding budget; the loop stops the moment
 * either is spent. Within a tier the order is SCC's default API order, so when a single tier alone exceeds
 * the cap the findings kept are the first N of that tier, not the worst N within it.
 */
@Slf4j
final class GcpSccFindingPager {

    static final List<String> SEVERITY_TIERS =
            List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "SEVERITY_UNSPECIFIED");

    static final int MAX_SCC_PAGE_SIZE = 1000;

    private GcpSccFindingPager() {
    }

    @FunctionalInterface
    interface PageFetcher {
        Page fetch(String filter, String pageToken, int pageSize) throws Exception;
    }

    record Page(List<GcpSecurityFinding> findings, String nextPageToken) {
    }

    /**
     * @param stoppedEarly true when a cap stopped the loop while more active findings may remain
     *                     (current tier had a next page, or later tiers were never queried)
     */
    record Result(int pagesFetched, long fetched, boolean stoppedEarly, String stopReason) {
    }

    static String filterFor(String tier) {
        return "state=\"ACTIVE\" AND severity=\"" + tier + "\"";
    }

    static Result fetch(String projectId, PageFetcher fetcher, Consumer<GcpSecurityFinding> sink,
                        int pageSize, int maxPages, int maxFindings) throws Exception {
        int size = Math.max(1, Math.min(MAX_SCC_PAGE_SIZE, pageSize));
        int pageCap = Math.max(1, maxPages);
        long findingCap = Math.max(1, maxFindings);
        int pages = 0;
        long fetched = 0;

        for (String tier : SEVERITY_TIERS) {
            String filter = filterFor(tier);
            String token = null;
            do {
                if (pages >= pageCap || fetched >= findingCap) {
                    String reason = pages >= pageCap ? "max-pages reached" : "max-findings reached";
                    log.warn("[GcpSecurity] SCC fetch for project={} truncated at the cap: pages={} (max-pages={}), "
                                    + "fetched={} (max-findings={}), reason={}; the remaining active findings were not read",
                            projectId, pages, pageCap, fetched, findingCap, reason);
                    return new Result(pages, fetched, true, reason);
                }
                Page page = fetcher.fetch(filter, token, size);
                pages++;
                if (page != null && page.findings() != null) {
                    for (GcpSecurityFinding f : page.findings()) {
                        sink.accept(f);
                        fetched++;
                    }
                }
                token = page == null ? null : page.nextPageToken();
            } while (token != null && !token.isBlank());
        }
        return new Result(pages, fetched, false, null);
    }
}
