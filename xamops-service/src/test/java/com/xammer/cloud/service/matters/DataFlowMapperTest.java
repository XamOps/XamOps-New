package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.dto.matters.datastores.DataFlowColumnPage;
import com.xammer.cloud.dto.matters.datastores.DataFlowDatabases;
import com.xammer.cloud.dto.matters.datastores.DataFlowErd;
import com.xammer.cloud.dto.matters.datastores.DataFlowRelations;
import com.xammer.cloud.dto.matters.datastores.DataFlowSchemas;
import com.xammer.cloud.dto.matters.datastores.DataFlowTablePage;
import com.xammer.cloud.dto.matters.datastores.DataFlowVendorPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Design 5.6.10, field mapping only; the paging arithmetic is the Data Flow service's (FEAT-002). */
class DataFlowMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final DataFlowMapper mapper = new DataFlowMapper();

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("databases: MYSQL and MARIADB are schema-less, case-insensitively")
    void databases() {
        assertThat(mapper.toDatabases(parse("""
                {"data": [{"id": "d-1", "name": "app_db", "engine": "POSTGRES"},
                          {"id": "d-2", "name": "shop", "engine": "mysql"},
                          {"id": "d-3", "name": "legacy", "engine": "MARIADB"},
                          {"id": "d-4", "name": "unknown"}],
                 "pagination": {"page": 1, "page_size": 100, "total": 4, "total_pages": 1}}
                """))).containsExactly(
                new DataFlowDatabases.Database("d-1", "app_db", "POSTGRES", false),
                new DataFlowDatabases.Database("d-2", "shop", "mysql", true),
                new DataFlowDatabases.Database("d-3", "legacy", "MARIADB", true),
                new DataFlowDatabases.Database("d-4", "unknown", null, false));
    }

    @Test
    void schemas() {
        assertThat(mapper.toSchemas(parse("{\"data\": [{\"id\": \"s-1\", \"name\": \"public\", \"owner\": \"x\"}]}")))
                .containsExactly(new DataFlowSchemas.Schema("s-1", "public"));
        assertThat(mapper.toSchemas(parse("{}"))).isEmpty();
    }

    @Test
    @DisplayName("tables: null row estimate stays null, the FK count falls back to foreign_keys_count, vendor totals")
    void tables() {
        DataFlowVendorPage<DataFlowTablePage.Table> page = mapper.toTablePage(parse("""
                {"data": [
                   {"id": "t-1", "name": "events", "row_count_estimate": 168, "foreign_key_count": 1,
                    "sensitive_count": 168, "table_type": "BASE TABLE", "indexes": [{"name": "i"}]},
                   {"id": "t-2", "name": "audit", "row_count_estimate": null, "foreign_keys_count": 2,
                    "sensitive_count": 0},
                   {"id": "t-3", "name": "bare"}],
                 "pagination": {"page": 1, "page_size": 100, "total": 156, "total_pages": 2}}
                """));
        assertThat(page.rows()).containsExactly(
                new DataFlowTablePage.Table("t-1", "events", 168L, 1L, 168L),
                new DataFlowTablePage.Table("t-2", "audit", null, 2L, 0L),
                new DataFlowTablePage.Table("t-3", "bare", null, null, null));
        assertThat(page.total()).isEqualTo(156);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("columns: native type, sensitive count and data element names; other fields dropped")
    void columns() throws Exception {
        DataFlowVendorPage<DataFlowColumnPage.Column> page = mapper.toColumnPage(parse("""
                {"data": [
                   {"id": "c-1", "name": "email", "native_type": "varchar", "logical_type": "STRING",
                    "is_primary_key": false, "foreign_key_constraint_names": ["fk_x"], "sensitive_count": 12,
                    "data_elements": [{"name": "EMAIL ADDRESS"}, {"name": null}, {"name": "PERSON NAME"}]},
                   {"id": "c-2", "name": "id", "native_type": "int8", "sensitive_count": 0, "data_elements": []}],
                 "pagination": {"total": 13, "total_pages": 1}}
                """));
        assertThat(page.rows()).containsExactly(
                new DataFlowColumnPage.Column("c-1", "email", "varchar", 12L,
                        java.util.List.of("EMAIL ADDRESS", "PERSON NAME"), false, true),
                new DataFlowColumnPage.Column("c-2", "id", "int8", 0L, java.util.List.of(), false, false));
        assertThat(page.total()).isEqualTo(13);
        String serialised = json.writeValueAsString(page);
        assertThat(serialised).doesNotContain("logical").doesNotContain("fk_x");
    }

    @Test
    @DisplayName("column flags: is_primary_key gives primaryKey, non-empty constraint names give foreignKey")
    void columnFlags() throws Exception {
        DataFlowVendorPage<DataFlowColumnPage.Column> cache = mapper.toColumnPage(parse("""
                {"data": [
                   {"id": "c-1", "name": "cache_key", "native_type": "varchar", "is_primary_key": true,
                    "foreign_key_constraint_names": []},
                   {"id": "c-2", "name": "payload", "native_type": "jsonb", "is_primary_key": "true",
                    "foreign_key_constraint_names": "MARKER-NOT-AN-ARRAY"}]}
                """));
        assertThat(cache.rows()).extracting(DataFlowColumnPage.Column::primaryKey).containsExactly(true, false);
        assertThat(cache.rows()).extracting(DataFlowColumnPage.Column::foreignKey).containsExactly(false, false);

        DataFlowVendorPage<DataFlowColumnPage.Column> invoice = mapper.toColumnPage(parse("""
                {"data": [
                   {"id": "c-1", "name": "id", "is_primary_key": true, "foreign_key_constraint_names": []},
                   {"id": "c-2", "name": "client_id", "is_primary_key": false,
                    "foreign_key_constraint_names": ["MARKER_client_fk"]},
                   {"id": "c-3", "name": "cloud_account_id",
                    "foreign_key_constraint_names": ["MARKER_account_fk", "MARKER_account_fk2"]},
                   {"id": "c-4", "name": "amount"}]}
                """));
        assertThat(invoice.rows()).extracting(DataFlowColumnPage.Column::primaryKey)
                .containsExactly(true, false, false, false);
        assertThat(invoice.rows()).extracting(DataFlowColumnPage.Column::foreignKey)
                .containsExactly(false, true, true, false);
        assertThat(json.writeValueAsString(invoice)).contains("\"primaryKey\":true").contains("\"foreignKey\":true")
                .doesNotContain("MARKER").doesNotContain("constraint");
    }

    // ── schema diagram ───────────────────────────────────────────────────────────────────────────

    private static String node(String id, String name, int sensitive) {
        return """
                {"id": "%s", "name": "%s", "table_type": "MARKER-TYPE", "row_count_estimate": null,
                 "unique_constraint_count": 7, "index_count": 8, "foreign_key_count": 9, "sensitive_count": %d}
                """.formatted(id, name, sensitive);
    }

    private static String edge(String source, String target, String sourceColumn, String targetColumn) {
        return """
                {"foreign_key_id": "MARKER-FKID", "constraint_name": "MARKER_constraint", "source_table_id": "%s",
                 "target_table_id": "%s", "target_database_name": "MARKER-DB", "target_schema_name": "public",
                 "target_table_name": "MARKER-TARGET",
                 "columns": [{"source_column_name": "%s", "target_column_name": "%s"}]}
                """.formatted(source, target, sourceColumn, targetColumn);
    }

    private static final String NODES = String.join(",",
            node("t-inv", "invoice", 3), node("t-acc", "cloud_account", 0), node("t-cli", "client", 2),
            node("t-dis", "discount", 0), node("t-lin", "invoice_line_item", 1), node("t-rep", "report_cache", 0));

    @Test
    @DisplayName("diagram, local invoice shape (first edge outgoing): FK chips cloud_account_id, id, client_id")
    void erdLocalInvoiceShape() throws Exception {
        DataFlowErd erd = mapper.toErd(parse("{\"nodes\": [" + NODES + "], \"edges\": [" + String.join(",",
                edge("t-inv", "t-acc", "cloud_account_id", "id"),
                edge("t-dis", "t-inv", "invoice_id", "id"),
                edge("t-inv", "t-cli", "client_id", "id"),
                edge("t-lin", "t-inv", "invoice_id", "id")) + "]}"));

        assertThat(erd.nodes()).containsExactly(
                new DataFlowErd.Node("t-inv", "invoice", 3L, List.of("cloud_account_id", "id", "client_id")),
                new DataFlowErd.Node("t-acc", "cloud_account", 0L, List.of("id")),
                new DataFlowErd.Node("t-cli", "client", 2L, List.of("id")),
                new DataFlowErd.Node("t-dis", "discount", 0L, List.of("invoice_id")),
                new DataFlowErd.Node("t-lin", "invoice_line_item", 1L, List.of("invoice_id")),
                new DataFlowErd.Node("t-rep", "report_cache", 0L, List.of()));
        assertThat(erd.edges()).containsExactly(
                new DataFlowErd.Edge("fk-0", "t-inv", "t-acc", false),
                new DataFlowErd.Edge("fk-1", "t-dis", "t-inv", false),
                new DataFlowErd.Edge("fk-2", "t-inv", "t-cli", false),
                new DataFlowErd.Edge("fk-3", "t-lin", "t-inv", false));
        assertThat(erd.externalTables()).isEmpty();
        assertThat(erd.nodesTruncated()).isFalse();
        assertThat(erd.edgesTruncated()).isFalse();

        assertThat(json.writeValueAsString(erd)).doesNotContain("MARKER").doesNotContain("foreign_key")
                .doesNotContain("foreignKeyCount").doesNotContain("constraint").doesNotContain("source_column")
                .doesNotContain("target_column").doesNotContain("columns").doesNotContain("row_count");
    }

    @Test
    @DisplayName("diagram, prod-like invoice shape (first edge incoming): chips id, client_id, cloud_account_id [O3]")
    void erdProdLikeInvoiceShape() {
        DataFlowErd erd = mapper.toErd(parse("{\"nodes\": [" + NODES + "], \"edges\": [" + String.join(",",
                edge("t-dis", "t-inv", "invoice_id", "id"),
                edge("t-inv", "t-cli", "client_id", "id"),
                edge("t-inv", "t-acc", "cloud_account_id", "id"),
                edge("t-lin", "t-inv", "invoice_id", "id")) + "]}"));
        assertThat(erd.nodes().get(0).keyColumns()).containsExactly("id", "client_id", "cloud_account_id");
    }

    @Test
    @DisplayName("diagram: a column named twice is listed once, and a self-reference adds source then target")
    void erdKeyColumnsDeduplicated() {
        DataFlowErd erd = mapper.toErd(parse("""
                {"nodes": [{"id": "t-emp", "name": "employee"}, {"id": "t-team", "name": "team"}],
                 "edges": [
                   {"source_table_id": "t-emp", "target_table_id": "t-emp",
                    "columns": [{"source_column_name": "manager_id", "target_column_name": "id"}]},
                   {"source_table_id": "t-emp", "target_table_id": "t-team",
                    "columns": [{"source_column_name": "team_id", "target_column_name": "id"},
                                {"source_column_name": "manager_id", "target_column_name": "lead_id"}]}]}
                """));
        assertThat(erd.nodes().get(0).keyColumns()).containsExactly("manager_id", "id", "team_id");
        assertThat(erd.nodes().get(1).keyColumns()).containsExactly("id", "lead_id");
        assertThat(erd.nodes().get(0).sensitiveCount()).isNull();
    }

    @Test
    @DisplayName("diagram: a target outside the diagram is external, one table per key; one without names is dropped")
    void erdExternalEdges() {
        DataFlowErd erd = mapper.toErd(parse("""
                {"nodes": [{"id": "t-ord", "name": "orders"}, {"name": "no id"}],
                 "edges": [
                   {"source_table_id": "t-ord", "target_table_id": "t-elsewhere", "target_schema_name": "billing",
                    "target_table_name": "customer", "columns": [{"source_column_name": "customer_id",
                                                                  "target_column_name": "id"}]},
                   {"source_table_id": "t-ord", "target_schema_name": "billing", "target_table_name": "customer",
                    "columns": [{"source_column_name": "payer_id", "target_column_name": "id"}]},
                   {"source_table_id": "t-ord", "target_table_id": "t-gone"},
                   {"source_table_id": "t-ord", "target_table_id": "t-gone", "target_table_name": "half"},
                   {"source_table_id": "t-unknown", "target_table_id": "t-ord"}]}
                """));
        assertThat(erd.nodes()).extracting(DataFlowErd.Node::id).containsExactly("t-ord");
        assertThat(erd.edges()).containsExactly(
                new DataFlowErd.Edge("fk-0", "t-ord", "ext:billing.customer", true),
                new DataFlowErd.Edge("fk-1", "t-ord", "ext:billing.customer", true));
        assertThat(erd.externalTables()).containsExactly(
                new DataFlowErd.ExternalTable("ext:billing.customer", "billing", "customer"));
        assertThat(erd.nodes().get(0).keyColumns()).containsExactly("customer_id", "payer_id");
    }

    @Test
    @DisplayName("diagram: 156 tables and 83 keys between them are all drawn, all internal")
    void erdAllInternal() {
        ObjectNode body = json.createObjectNode();
        ArrayNode nodes = body.putArray("nodes");
        for (int i = 0; i < 156; i++) {
            nodes.addObject().put("id", "t-" + i).put("name", "table_" + i).put("sensitive_count", i % 3);
        }
        ArrayNode edges = body.putArray("edges");
        for (int i = 0; i < 83; i++) {
            ObjectNode edge = edges.addObject().put("source_table_id", "t-" + (i + 1)).put("target_table_id", "t-0");
            edge.putArray("columns").addObject().put("source_column_name", "parent_id").put("target_column_name", "id");
        }
        DataFlowErd erd = mapper.toErd(body);
        assertThat(erd.nodes()).hasSize(156);
        assertThat(erd.edges()).hasSize(83).noneMatch(DataFlowErd.Edge::external);
        assertThat(erd.nodes().get(0).keyColumns()).containsExactly("id");
    }

    @Test
    @DisplayName("diagram caps: more than 1,000 tables sets only nodesTruncated, more than 3,000 keys only edgesTruncated")
    void erdCaps() {
        ObjectNode manyNodes = json.createObjectNode();
        ArrayNode nodes = manyNodes.putArray("nodes");
        for (int i = 0; i < 1_001; i++) {
            nodes.addObject().put("id", "t-" + i).put("name", "table_" + i);
        }
        ArrayNode edges = manyNodes.putArray("edges");
        edges.addObject().put("source_table_id", "t-1000").put("target_table_id", "t-0");
        edges.addObject().put("source_table_id", "t-1").put("target_table_id", "t-1000")
                .put("target_schema_name", "public").put("target_table_name", "table_1000");
        DataFlowErd nodeCapped = mapper.toErd(manyNodes);
        assertThat(nodeCapped.nodes()).hasSize(1_000);
        assertThat(nodeCapped.nodesTruncated()).isTrue();
        assertThat(nodeCapped.edgesTruncated()).isFalse();
        assertThat(nodeCapped.edges()).containsExactly(
                new DataFlowErd.Edge("fk-1", "t-1", "ext:public.table_1000", true));

        ObjectNode manyEdges = json.createObjectNode();
        manyEdges.putArray("nodes").addObject().put("id", "t-a");
        ArrayNode keys = manyEdges.putArray("edges");
        for (int i = 0; i < 3_001; i++) {
            keys.addObject().put("source_table_id", "t-a").put("target_table_id", "t-a");
        }
        DataFlowErd edgeCapped = mapper.toErd(manyEdges);
        assertThat(edgeCapped.edges()).hasSize(3_000);
        assertThat(edgeCapped.edgesTruncated()).isTrue();
        assertThat(edgeCapped.nodesTruncated()).isFalse();

        DataFlowErd empty = mapper.toErd(parse("{}"));
        assertThat(empty.nodes()).isEmpty();
        assertThat(empty.edges()).isEmpty();
        assertThat(empty.externalTables()).isEmpty();
    }

    // ── table relations ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("relations, invoice shape: outgoing and incoming in API order with column pairs, ids dropped")
    void relationsInvoiceShape() throws Exception {
        DataFlowRelations relations = mapper.toRelations(parse("""
                {"table_id": "MARKER-TABLE",
                 "outgoing": [
                   {"foreign_key_id": "MARKER-FK1", "constraint_name": "MARKER_c1", "other_table_id": "MARKER-T1",
                    "other_database_name": "MARKER-DB", "other_schema_name": "public", "other_table_name": "cloud_account",
                    "columns": [{"source_column_name": "cloud_account_id", "target_column_name": "id"}]},
                   {"foreign_key_id": "MARKER-FK2", "other_schema_name": "public", "other_table_name": "client",
                    "columns": [{"source_column_name": "client_id", "target_column_name": "id"}]}],
                 "incoming": [
                   {"other_schema_name": "public", "other_table_name": "discount",
                    "columns": [{"source_column_name": "invoice_id", "target_column_name": "id"}]},
                   {"other_schema_name": "public", "other_table_name": "invoice_line_item",
                    "columns": [{"source_column_name": "invoice_id", "target_column_name": "id"}]},
                   {"other_schema_name": "public", "other_table_name": " "}]}
                """));
        assertThat(relations.outgoing()).containsExactly(
                new DataFlowRelations.Relation("public", "cloud_account",
                        List.of(new DataFlowRelations.ColumnPair("cloud_account_id", "id"))),
                new DataFlowRelations.Relation("public", "client",
                        List.of(new DataFlowRelations.ColumnPair("client_id", "id"))));
        assertThat(relations.incoming()).containsExactly(
                new DataFlowRelations.Relation("public", "discount",
                        List.of(new DataFlowRelations.ColumnPair("invoice_id", "id"))),
                new DataFlowRelations.Relation("public", "invoice_line_item",
                        List.of(new DataFlowRelations.ColumnPair("invoice_id", "id"))));
        assertThat(json.writeValueAsString(relations)).doesNotContain("MARKER").doesNotContain("constraint")
                .doesNotContain("database").doesNotContain("table_id").doesNotContain("foreign");
    }

    @Test
    @DisplayName("relations: a table without keys gives two empty lists")
    void relationsEmpty() {
        DataFlowRelations relations = mapper.toRelations(parse("{\"table_id\": \"t\", \"outgoing\": [], \"incoming\": []}"));
        assertThat(relations.outgoing()).isEmpty();
        assertThat(relations.incoming()).isEmpty();
        assertThat(mapper.toRelations(parse("{}"))).isEqualTo(new DataFlowRelations(List.of(), List.of()));
    }

    @Test
    void vendorTotalsDefaultToZero() {
        assertThat(mapper.vendorTotal(parse("{}"))).isZero();
        assertThat(mapper.vendorTotalPages(parse("{\"pagination\": {\"total_pages\": \"3\"}}"))).isZero();
        assertThat(mapper.vendorTotalPages(parse("{\"pagination\": {\"total_pages\": 3}}"))).isEqualTo(3);
    }
}
