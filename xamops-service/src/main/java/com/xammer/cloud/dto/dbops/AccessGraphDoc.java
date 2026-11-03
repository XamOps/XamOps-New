package com.xammer.cloud.dto.dbops;

import java.util.List;

/**
 * Pillar-B access graph response (API-B1, §6.4). Nodes/edges are already in cytoscape element form
 * ({@code data} + a {@code kind}/{@code severity} that the frontend maps to {@code classes}); the
 * frontend builder only forwards them. {@code edges[]} source/target are always some {@code nodes[].id}
 * (invariant). The {@code principal} rank is bounded server-side by {@code ?topN}; the remainder is
 * reported in {@link AgMeta#hiddenPrincipalCount()}.
 */
public record AccessGraphDoc(
        String dsnAlias,
        String collectedAt,                   // ISO-8601, or null when no data
        boolean stale,
        List<AgNode> nodes,
        List<AgEdge> edges,
        List<AgInsight> insights,
        AgMeta meta,
        List<String> availableDsnAliases) {   // MEDIUM-1 selector inventory

    public static AccessGraphDoc empty() {
        return new AccessGraphDoc(null, null, false, List.of(), List.of(), List.of(),
                new AgMeta(0, false, 0), List.of());
    }

    /** One cytoscape node. {@code kind}/{@code severity} become cytoscape {@code classes}. */
    public record AgNode(
            String id,          // "ds:{dsn}" | "priv:{class}" | "role:{rolname}" | "principal:{rolname}"
            String kind,        // datastore | privilege | role | principal
            String label,
            AgNodeData data,
            String severity) {  // null | warning | critical (colour-for-meaning only)
    }

    /** Detail-panel payload — union by kind, only the relevant fields are populated. */
    public record AgNodeData(
            // datastore
            String dbHost, Integer roleCount, Integer principalCount,
            // privilege
            String privilegeClass, Integer objectCount, Boolean hasPublic,
            // role
            Boolean isGroup, List<String> memberOf, List<String> grantsClasses,
            // principal
            Boolean canLogin, Boolean isSuperuser, Boolean bypassRls, Integer connLimit,
            List<String> inheritedFrom, List<String> effectivePrivilegeClasses, List<String> flags) {
    }

    public record AgEdge(
            String id,       // "{source}->{target}"
            String source,   // == some nodes[].id
            String target,   // == some nodes[].id
            String kind) {   // datastore-class | holds-class | membership
    }

    public record AgInsight(
            String id,         // "over-privileged:{principal}" | "public-grant:{scope}" | "unused:{principal}"
            String severity,   // info | warning | critical
            String subject,
            String title,
            String detail) {
    }

    public record AgMeta(
            int hiddenPrincipalCount,   // principals beyond ?topN not emitted (rev-3 Finding 12)
            boolean truncated,          // hiddenPrincipalCount > 0
            int totalPrincipals) {
    }
}
