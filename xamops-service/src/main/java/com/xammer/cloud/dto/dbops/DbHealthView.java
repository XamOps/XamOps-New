package com.xammer.cloud.dto.dbops;

import java.util.List;

/**
 * The Pillar-A Health view for one DSN (API-A1, §6.4). An empty {@code findings} with
 * {@code status=ok|collecting|no_data} is legitimate — never a fabricated "100 — healthy" (the
 * COR-1/COR-22 mistake). {@code availableDsnAliases} drives the per-DSN selector (MEDIUM-1), and
 * when {@code dsnAlias} is omitted the endpoint defaults to the first alias sorted ascending.
 */
public record DbHealthView(
        String dsnAlias,
        String dbHost,                       // display label only, never a cache key (§6.1)
        String status,                       // ok | warning | critical | collecting | no_data
        String collectedAt,                  // ISO-8601, or null when no data
        boolean stale,                       // older than 2× the collector interval (PERF-10)
        int dsnCount,                        // >1 drives the DSN selector
        List<String> availableDsnAliases,    // every alias found for this agent (MEDIUM-1)
        List<Finding> findings) {            // ranked worst-first

    /** Honest empty document used for no-agent-data and the fail-closed (flag-off) response. */
    public static DbHealthView empty(String status) {
        return new DbHealthView(null, null, status, null, false, 0, List.of(), List.of());
    }
}
