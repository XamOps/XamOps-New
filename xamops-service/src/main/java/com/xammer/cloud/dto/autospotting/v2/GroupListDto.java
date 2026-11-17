package com.xammer.cloud.dto.autospotting.v2;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The group list response {@code {items, partial, failedLocations[{location, code}], asOf}}
 * (design-ux-ops.md §4.6, FR-OBS-06). A location that did not finish inside
 * {@code autospotting.list.budget} comes back in {@code failedLocations} with code {@code TIMEOUT} and
 * the response is {@code partial=true}, so a slow region is never read as "no groups". The response
 * also carries per-code blocker counts so {@code EC2_HEALTH_WITH_LB} exclusions can be counted
 * (NO-07), and KPIs that carry "(partial)" client-side when {@code partial} is true.
 */
public record GroupListDto(
        List<GroupRowDto> items,
        boolean partial,
        List<FailedLocation> failedLocations,
        Instant asOf,
        Map<String, Integer> blockedCounts,
        Kpis kpis) {

    public GroupListDto {
        items = items == null ? List.of() : List.copyOf(items);
        failedLocations = failedLocations == null ? List.of() : List.copyOf(failedLocations);
        blockedCounts = blockedCounts == null ? Map.of() : Map.copyOf(blockedCounts);
    }

    /** A region (or project) that could not be read, with the reason code (e.g. {@code TIMEOUT}, {@code PERMISSION}). */
    public record FailedLocation(String location, String code) {
    }

    /**
     * List KPIs. {@code potentialMonthlySavings} sums only priced groups; {@code savingsUnknownGroups}
     * counts the groups whose price is unknown, so the figure is never shown as a complete total
     * when some are "Not priced".
     */
    public record Kpis(int groups, int eligible, int blocked, Double potentialMonthlySavings,
                       int savingsUnknownGroups) {
    }
}
