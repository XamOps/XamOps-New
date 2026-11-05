package com.xammer.cloud.service;

import com.xammer.cloud.dto.dbops.AgentFkEdgeDto;
import com.xammer.cloud.dto.dbops.AgentFkGraphDoc;
import com.xammer.cloud.dto.dbops.AgentFkTableDto;
import com.xammer.cloud.dto.dbops.AgentTableActivityDto;
import com.xammer.cloud.dto.dbops.FkGraphDoc;
import com.xammer.cloud.dto.dbops.FkGraphDoc.FkNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pillar C read-side builder (§7.3): the edges[] ⊆ nodes[] invariant via backfill-then-drop with the
 * ceil(0.1 × topN) boundary cap and a counted droppedEdgeCount, deterministic ranking when sizeBytes
 * is 0, and the MEDIUM-2 activityScore normalization.
 */
class FkGraphBuilderTest {

    private final FkGraphService service = new FkGraphService(
            mock(com.xammer.cloud.repository.DbAgentConfigRepository.class),
            mock(com.xammer.cloud.repository.DbAgentDataCacheRepository.class));

    private AgentFkTableDto table(String schema, String name, long oid, long size, long rows) {
        AgentFkTableDto t = new AgentFkTableDto();
        t.setSchema(schema);
        t.setName(name);
        t.setOid(oid);
        t.setSizeBytes(size);
        t.setRowEstimate(rows);
        return t;
    }

    private AgentFkEdgeDto edge(String childSchema, String childTable, String parentSchema, String parentTable, String name) {
        AgentFkEdgeDto e = new AgentFkEdgeDto();
        e.setChildSchema(childSchema);
        e.setChildTable(childTable);
        e.setParentSchema(parentSchema);
        e.setParentTable(parentTable);
        e.setConstraintName(name);
        e.setColumns(List.of("fk_id"));
        e.setOnDelete("NO ACTION");
        e.setOnUpdate("NO ACTION");
        return e;
    }

    private AgentTableActivityDto activity(String schema, String table, long seq, long idx, long ins, long upd, long del) {
        AgentTableActivityDto a = new AgentTableActivityDto();
        a.setDsnAlias("db-0");
        a.setSchemaName(schema);
        a.setTableName(table);
        a.setSeqScan(seq);
        a.setIdxScan(idx);
        a.setNTupIns(ins);
        a.setNTupUpd(upd);
        a.setNTupDel(del);
        return a;
    }

    private AgentFkGraphDoc doc(List<AgentFkTableDto> tables, List<AgentFkEdgeDto> edges) {
        AgentFkGraphDoc d = new AgentFkGraphDoc();
        d.setDsnAlias("db-0");
        d.setDbHost("pg.internal");
        d.setCollectedAt("2026-10-29T12:00:00Z");
        d.setTables(tables);
        d.setFkEdges(edges);
        d.setTotalFkTables(tables.size());
        d.setTotalFkEdges(edges.size());
        return d;
    }

    @Test
    void keepsConsistentEdgesAndNormalizesActivityScore() {
        AgentFkGraphDoc d = doc(
                List.of(table("public", "a", 1, 1000, 100),
                        table("public", "b", 2, 500, 50),
                        table("public", "c", 3, 10, 1)),       // in graph but no activity row → null score
                List.of(edge("public", "b", "public", "a", "fk_b_a")));
        List<AgentTableActivityDto> activity = List.of(
                activity("public", "a", 10, 0, 0, 0, 0),         // raw 10
                activity("public", "b", 90, 0, 0, 0, 0));         // raw 90 → max

        FkGraphDoc out = service.build(d, activity, null, 300, null, false, List.of("db-0"));

        var ids = out.nodes().stream().map(FkNode::id).toList();
        assertThat(ids).contains("public.a", "public.b", "public.c");
        assertThat(out.edges()).allSatisfy(e -> {
            assertThat(ids).contains(e.source());
            assertThat(ids).contains(e.target());
        });
        assertThat(out.edges()).singleElement().satisfies(e -> {
            assertThat(e.source()).isEqualTo("public.b"); // child → parent
            assertThat(e.target()).isEqualTo("public.a");
        });
        FkNode a = node(out, "public.a");
        FkNode b = node(out, "public.b");
        FkNode c = node(out, "public.c");
        assertThat(b.activityScore()).isEqualTo(1.0);          // max raw → 1.0
        assertThat(a.activityScore()).isEqualTo(10.0 / 90.0);
        assertThat(c.activityScore()).isNull();                 // absent from activity feed
    }

    @Test
    void backfillsBoundaryUpToCapThenDropsAndCounts() {
        // topN=1 → only the largest table ("public.child") is selected; cap = ceil(0.1×1) = 1.
        AgentFkGraphDoc d = doc(
                List.of(table("public", "child", 1, 1000, 100),
                        table("public", "p1", 2, 3, 1),
                        table("public", "p2", 3, 2, 1),
                        table("public", "p3", 4, 1, 1)),
                List.of(edge("public", "child", "public", "p1", "fk1"),
                        edge("public", "child", "public", "p2", "fk2"),
                        edge("public", "child", "public", "p3", "fk3")));

        FkGraphDoc out = service.build(d, List.of(), null, 1, null, false, List.of("db-0"));

        assertThat(out.meta().tableCount()).isEqualTo(1);        // only "child" selected
        assertThat(out.meta().boundaryNodeCount()).isEqualTo(1); // cap = 1
        assertThat(out.meta().droppedEdgeCount()).isEqualTo(2);  // p2, p3 edges dropped
        assertThat(out.meta().truncated()).isTrue();
        // edges[] ⊆ nodes[] still holds after the drop.
        var ids = out.nodes().stream().map(FkNode::id).toList();
        assertThat(out.edges()).hasSize(1);
        assertThat(out.edges()).allSatisfy(e -> {
            assertThat(ids).contains(e.source());
            assertThat(ids).contains(e.target());
        });
        assertThat(node(out, "public.p1").boundary()).isTrue(); // backfilled endpoint is flagged boundary
    }

    @Test
    void rankingIsDeterministicWhenSizeBytesZero() {
        // All sizes 0 → order falls to rowEstimate desc, then activityScore, then oid asc.
        List<AgentFkTableDto> tables = new ArrayList<>(List.of(
                table("public", "t_small", 10, 0, 5),
                table("public", "t_big", 11, 0, 500),
                table("public", "t_mid", 12, 0, 50)));
        AgentFkGraphDoc d = doc(tables, List.of());

        FkGraphDoc out = service.build(d, List.of(), null, 2, null, false, List.of("db-0"));
        FkGraphDoc again = service.build(d, List.of(), null, 2, null, false, List.of("db-0"));

        var ids = out.nodes().stream().map(FkNode::id).toList();
        assertThat(ids).containsExactlyInAnyOrder("public.t_big", "public.t_mid"); // top-2 by rows
        assertThat(ids).doesNotContain("public.t_small");
        // Deterministic across runs.
        assertThat(again.nodes().stream().map(FkNode::id).toList())
                .containsExactlyElementsOf(ids);
    }

    @Test
    void declaredFkCountDrivesSparseMessage() {
        AgentFkGraphDoc d = doc(List.of(table("public", "lonely", 1, 100, 10)), List.of());
        d.setTotalFkEdges(0);
        FkGraphDoc out = service.build(d, List.of(), null, 300, null, false, List.of("db-0"));
        assertThat(out.meta().declaredFkCount()).isZero();
        assertThat(out.edges()).isEmpty();
    }

    private static FkNode node(FkGraphDoc doc, String id) {
        return doc.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }
}
