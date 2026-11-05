package com.xammer.cloud.dto.dbops;

import java.util.List;

/**
 * Pillar-C FK table-graph response (API-C1, §6.4). Same cytoscape-ready shape as {@link AccessGraphDoc}.
 * {@code edges[]} source/target are always some {@code nodes[].id} — the §7.3 backfill-then-drop
 * builder enforces this so cytoscape never throws on a dangling edge. Dropped edges are counted in
 * {@link FkMeta#droppedEdgeCount()}, never silently lost.
 */
public record FkGraphDoc(
        String dsnAlias,
        String collectedAt,                  // ISO-8601, or null when no data
        boolean stale,
        List<FkNode> nodes,
        List<FkEdge> edges,
        FkMeta meta,
        List<String> availableDsnAliases) {  // MEDIUM-1 selector inventory

    public static FkGraphDoc empty() {
        return new FkGraphDoc(null, null, false, List.of(), List.of(),
                new FkMeta(0, 0, 0, 0, false), List.of());
    }

    public record FkNode(
            String id,              // "{schema}.{table}"
            String schema,
            String table,
            long sizeBytes,         // catalog-derived; always present, non-negative
            long rowEstimate,       // GREATEST(reltuples,0); always present, non-negative
            Double activityScore,   // nullable — only for tables in the top-100 db-table-activity feed
            boolean boundary) {     // true for a backfilled out-of-selection endpoint (muted style)
    }

    public record FkEdge(
            String id,              // constraintName (fallback "{child}->{parent}")
            String source,          // child node id (edge directed child→parent)
            String target,          // parent node id
            List<String> columns,
            String onDelete,
            String onUpdate) {
    }

    public record FkMeta(
            int declaredFkCount,    // total FKs the schema declares (drives the honest sparse message)
            int tableCount,         // nodes emitted (excl. boundary)
            int boundaryNodeCount,  // backfilled endpoints (≤ ceil(0.1 × topN))
            int droppedEdgeCount,   // edges dropped after the boundary cap → "N relationships hidden"
            boolean truncated) {    // droppedEdgeCount > 0 OR collector-side table ceiling hit
    }
}
