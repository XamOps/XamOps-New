package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.DbAgentDataCache;
import com.xammer.cloud.dto.dbops.AgentFkEdgeDto;
import com.xammer.cloud.dto.dbops.AgentFkGraphDoc;
import com.xammer.cloud.dto.dbops.AgentFkTableDto;
import com.xammer.cloud.dto.dbops.AgentTableActivityDto;
import com.xammer.cloud.dto.dbops.FkGraphDoc;
import com.xammer.cloud.dto.dbops.FkGraphDoc.FkEdge;
import com.xammer.cloud.dto.dbops.FkGraphDoc.FkMeta;
import com.xammer.cloud.dto.dbops.FkGraphDoc.FkNode;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pillar C — derives the cytoscape FK table-graph ({@link FkGraphDoc}) from the agent's raw
 * {@link AgentFkGraphDoc} (§7.3). It applies the read-time {@code schema}/{@code topN}/{@code focus}
 * filters, enriches {@code activityScore} from the db-table-activity feed (MEDIUM-2), and owns the
 * {@code edges[] ⊆ nodes[]} invariant via the backfill-then-drop rule: a surviving edge's
 * out-of-selection endpoint is backfilled as a flagged boundary node up to {@code ceil(0.1 × topN)},
 * then remaining out-of-set edges are dropped and counted in {@code meta.droppedEdgeCount} — never
 * silently lost. {@link #build} is pure for unit testing; {@link #buildForAccount} reads cache.
 */
@Service
public class FkGraphService {

    private static final Logger logger = LoggerFactory.getLogger(FkGraphService.class);

    static final int DEFAULT_TOPN = 300;
    static final int MIN_TOPN = 1;
    static final int MAX_TOPN = 2000;

    private final DbAgentConfigRepository dbAgentConfigRepository;
    private final DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private final ObjectMapper objectMapper;

    public FkGraphService(DbAgentConfigRepository dbAgentConfigRepository,
                          DbAgentDataCacheRepository dbAgentDataCacheRepository) {
        this.dbAgentConfigRepository = dbAgentConfigRepository;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public static int clampTopN(Integer topN) {
        if (topN == null) return DEFAULT_TOPN;
        return Math.max(MIN_TOPN, Math.min(MAX_TOPN, topN));
    }

    // ───────────────────────────── cache-reading entry point ─────────────────────────────

    public FkGraphDoc buildForAccount(CloudAccount account, String dsnAlias, String schema,
                                      Integer topN, String focus) {
        List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(account.getId());

        List<AgentFkGraphDoc> docs = new ArrayList<>();
        List<AgentTableActivityDto> activity = new ArrayList<>();
        for (DbAgentConfig agent : agents) {
            for (DbAgentDataCache c : dbAgentDataCacheRepository.findByDbAgentId(agent.getId())) {
                if (c.getDataType() == null || c.getDataJson() == null) continue;
                if (c.getDataType().startsWith("db-fk-graph:")) {
                    AgentFkGraphDoc doc = parseDoc(c.getDataJson());
                    if (doc != null) docs.add(doc);
                }
            }
            Optional<DbAgentDataCache> ta = dbAgentDataCacheRepository
                    .findByDbAgentIdAndDataType(agent.getId(), "db-table-activity");
            ta.ifPresent(c -> activity.addAll(parseList(c.getDataJson(),
                    new TypeReference<List<AgentTableActivityDto>>() {})));
        }

        if (docs.isEmpty()) {
            return FkGraphDoc.empty();
        }

        TreeSet<String> aliasSet = new TreeSet<>();
        for (AgentFkGraphDoc d : docs) {
            if (d.getDsnAlias() != null) aliasSet.add(d.getDsnAlias());
        }
        List<String> availableDsnAliases = new ArrayList<>(aliasSet);
        String selected = (dsnAlias != null && aliasSet.contains(dsnAlias))
                ? dsnAlias : availableDsnAliases.get(0);

        AgentFkGraphDoc doc = docs.stream()
                .filter(d -> selected.equals(d.getDsnAlias())).findFirst().orElse(null);
        if (doc == null) return FkGraphDoc.empty();

        List<AgentTableActivityDto> dsnActivity = activity.stream()
                .filter(a -> selected.equals(a.getDsnAlias())).toList();
        boolean stale = DbHealthConsoleService.isStale(doc.getCollectedAt(),
                DbHealthConsoleService.DEFAULT_COLLECTOR_INTERVAL_SECS);
        return build(doc, dsnActivity, schema, clampTopN(topN), focus, stale, availableDsnAliases);
    }

    // ───────────────────────────── pure derivation ─────────────────────────────

    public FkGraphDoc build(AgentFkGraphDoc doc, List<AgentTableActivityDto> activity, String schema,
                            int topN, String focus, boolean stale, List<String> availableDsnAliases) {
        List<AgentFkTableDto> tables = doc.getTables() != null ? doc.getTables() : List.of();
        List<AgentFkEdgeDto> fkEdges = doc.getFkEdges() != null ? doc.getFkEdges() : List.of();

        Map<String, AgentFkTableDto> byId = new HashMap<>();
        for (AgentFkTableDto t : tables) byId.put(tableId(t.getSchema(), t.getName()), t);

        // Raw activity score per table (MEDIUM-2): seqScan + idxScan + nTupIns + nTupUpd + nTupDel.
        Map<String, Long> rawActivity = new HashMap<>();
        if (activity != null) {
            for (AgentTableActivityDto a : activity) {
                long raw = a.getSeqScan() + a.getIdxScan() + a.getNTupIns() + a.getNTupUpd() + a.getNTupDel();
                rawActivity.put(tableId(a.getSchemaName(), a.getTableName()), raw);
            }
        }

        // ── 1. Select the node set ──
        LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
        if (focus != null && !focus.isBlank()) {
            String f = focus.trim();
            selectedIds.add(f);
            for (AgentFkEdgeDto e : fkEdges) {
                String child = tableId(e.getChildSchema(), e.getChildTable());
                String parent = tableId(e.getParentSchema(), e.getParentTable());
                if (child.equals(f)) selectedIds.add(parent);
                if (parent.equals(f)) selectedIds.add(child);
            }
        } else {
            List<AgentFkTableDto> candidates = new ArrayList<>();
            for (AgentFkTableDto t : tables) {
                if (schema == null || schema.isBlank() || schema.equals(t.getSchema())) candidates.add(t);
            }
            candidates.sort(Comparator
                    .comparingLong(AgentFkTableDto::getSizeBytes)
                    .thenComparingLong(AgentFkTableDto::getRowEstimate)
                    .thenComparingLong((AgentFkTableDto t) -> rawActivity.getOrDefault(tableId(t.getSchema(), t.getName()), 0L))
                    .reversed()
                    .thenComparingLong(AgentFkTableDto::getOid));
            for (int i = 0; i < Math.min(topN, candidates.size()); i++) {
                AgentFkTableDto t = candidates.get(i);
                selectedIds.add(tableId(t.getSchema(), t.getName()));
            }
        }

        // ── 2. Edge consistency: backfill out-of-selection endpoints up to the cap, then drop ──
        int boundaryCap = (int) Math.ceil(0.1 * topN);
        LinkedHashSet<String> boundaryIds = new LinkedHashSet<>();
        List<FkEdge> keptEdges = new ArrayList<>();
        int droppedEdgeCount = 0;
        for (AgentFkEdgeDto e : fkEdges) {
            String child = tableId(e.getChildSchema(), e.getChildTable());
            String parent = tableId(e.getParentSchema(), e.getParentTable());
            boolean childIn = selectedIds.contains(child);
            boolean parentIn = selectedIds.contains(parent);
            if (!childIn && !parentIn) continue;          // both out of selection — not a surviving edge
            if (!(childIn && parentIn)) {
                String outside = childIn ? parent : child;
                if (!boundaryIds.contains(outside)) {
                    if (boundaryIds.size() < boundaryCap) {
                        boundaryIds.add(outside);
                    } else {
                        droppedEdgeCount++;               // cap reached — drop and count (never silently lost)
                        continue;
                    }
                }
            }
            keptEdges.add(new FkEdge(
                    e.getConstraintName() != null && !e.getConstraintName().isEmpty()
                            ? e.getConstraintName() : child + "->" + parent,
                    child, parent,
                    e.getColumns() != null ? e.getColumns() : List.of(),
                    e.getOnDelete(), e.getOnUpdate()));
        }

        // ── 3. Normalize activityScore over the EMITTED node set, then build nodes ──
        LinkedHashSet<String> allIds = new LinkedHashSet<>();
        allIds.addAll(selectedIds);
        allIds.addAll(boundaryIds);
        long maxRaw = 0;
        for (String id : allIds) maxRaw = Math.max(maxRaw, rawActivity.getOrDefault(id, 0L));

        List<FkNode> nodes = new ArrayList<>();
        for (String id : selectedIds) nodes.add(toNode(id, byId, rawActivity, maxRaw, false));
        for (String id : boundaryIds) nodes.add(toNode(id, byId, rawActivity, maxRaw, true));

        int declaredFkCount = doc.getTotalFkEdges() > 0 ? doc.getTotalFkEdges() : fkEdges.size();
        boolean truncated = droppedEdgeCount > 0 || doc.isTruncated();
        FkMeta meta = new FkMeta(declaredFkCount, selectedIds.size(), boundaryIds.size(),
                droppedEdgeCount, truncated);

        return new FkGraphDoc(doc.getDsnAlias(), doc.getCollectedAt(), stale, nodes, keptEdges, meta,
                availableDsnAliases != null ? availableDsnAliases : List.of());
    }

    private static FkNode toNode(String id, Map<String, AgentFkTableDto> byId,
                                 Map<String, Long> rawActivity, long maxRaw, boolean boundary) {
        AgentFkTableDto t = byId.get(id);
        String schema = t != null ? t.getSchema() : schemaOf(id);
        String table = t != null ? t.getName() : tableOf(id);
        long size = t != null ? t.getSizeBytes() : 0;
        long rows = t != null ? t.getRowEstimate() : 0;
        Double activityScore;
        if (!rawActivity.containsKey(id)) {
            activityScore = null;               // absent from the top-100 activity feed
        } else if (maxRaw <= 0) {
            activityScore = 0.0;
        } else {
            activityScore = rawActivity.get(id) / (double) maxRaw;
        }
        return new FkNode(id, schema, table, size, rows, activityScore, boundary);
    }

    // ── helpers ──

    private static String tableId(String schema, String table) {
        return (schema == null ? "" : schema) + "." + (table == null ? "" : table);
    }

    private static String schemaOf(String id) {
        int dot = id.indexOf('.');
        return dot >= 0 ? id.substring(0, dot) : "";
    }

    private static String tableOf(String id) {
        int dot = id.indexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }

    private AgentFkGraphDoc parseDoc(String json) {
        try {
            return objectMapper.readValue(json, AgentFkGraphDoc.class);
        } catch (Exception e) {
            logger.warn("[FkGraph] parse failed: {}", e.getMessage());
            return null;
        }
    }

    private <T> List<T> parseList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<T> items = objectMapper.readValue(json, typeRef);
            return items != null ? items : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
