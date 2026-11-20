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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Design 5.6.10 paging over 100-row vendor pages, the search loop and the tree loops, with the real
 * mapper, a mocked client generating synthetic vendor pages, and an in-memory cache.
 */
class DatastoreDataFlowServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String SOURCE_ID = "0123456789abcdef01234567";
    private static final String SCHEMA = "0000000a-0000-4000-8000-00000000000a";
    private static final String DATABASE = "0000000d-0000-4000-8000-00000000000d";
    private static final String TABLE = "0000000c-0000-4000-8000-00000000000c";

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private InMemoryRedisCache cache;
    private DatastoreDataFlowService service;

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        cache = new InMemoryRedisCache(events);
        when(resolver.resolve(10L)).thenAnswer(inv -> {
            events.add("resolve");
            return CREDENTIAL;
        });
        service = new DatastoreDataFlowService(resolver, apiClient, new DataFlowMapper(), cache);
    }

    // ── synthetic vendor pages ───────────────────────────────────────────────────────────────────

    /** Every tenth table is an "Agent_Events_…" table; the rest are "orders_…". */
    private static String tableName(int index) {
        return index % 10 == 0 ? "Agent_Events_" + index : "orders_" + index;
    }

    private ObjectNode pageOf(ArrayNode data, int vendorPage, int total) {
        ObjectNode body = json.createObjectNode();
        body.set("data", data);
        ObjectNode pagination = body.putObject("pagination");
        pagination.put("page", vendorPage);
        pagination.put("page_size", 100);
        pagination.put("total", total);
        pagination.put("total_pages", (total + 99) / 100);
        return body;
    }

    private JsonNode tablePage(int vendorPage, int total) {
        ArrayNode data = json.createArrayNode();
        for (int i = (vendorPage - 1) * 100; i < Math.min(vendorPage * 100, total); i++) {
            ObjectNode table = data.addObject();
            table.put("id", "table-" + i);
            table.put("name", tableName(i));
            table.put("row_count_estimate", i);
            table.put("foreign_key_count", 1);
            table.put("sensitive_count", 0);
        }
        return pageOf(data, vendorPage, total);
    }

    private JsonNode columnPage(int vendorPage, int total) {
        ArrayNode data = json.createArrayNode();
        for (int i = (vendorPage - 1) * 100; i < Math.min(vendorPage * 100, total); i++) {
            ObjectNode column = data.addObject();
            column.put("id", "column-" + i);
            column.put("name", "col_" + i);
            column.put("native_type", "varchar");
            column.put("sensitive_count", 0);
            column.putArray("data_elements");
        }
        return pageOf(data, vendorPage, total);
    }

    /** One item per page, so the page count is the item count. */
    private JsonNode treePage(int vendorPage, int totalPages, String engine) {
        ArrayNode data = json.createArrayNode();
        ObjectNode item = data.addObject();
        item.put("id", "item-" + vendorPage);
        item.put("name", "item_" + vendorPage);
        item.put("engine", engine);
        ObjectNode body = json.createObjectNode();
        body.set("data", data);
        body.putObject("pagination").put("page", vendorPage).put("page_size", 100).put("total", totalPages)
                .put("total_pages", totalPages);
        return body;
    }

    private void schemaWithTables(int total) {
        when(apiClient.schemaTables(eq(CREDENTIAL), eq(SCHEMA), anyInt(), eq(100)))
                .thenAnswer(inv -> tablePage(inv.getArgument(2), total));
    }

    private static List<String> names(DataFlowTablePage page) {
        return page.rows().stream().map(DataFlowTablePage.Table::name).toList();
    }

    private static List<String> expectedNames(int fromInclusive, int toExclusive) {
        return IntStream.range(fromInclusive, toExclusive).mapToObj(DatastoreDataFlowServiceTest::tableName).toList();
    }

    // ── paging without search ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UI page 2 of size 10 is rows 10-19 of vendor page 1")
    void slicesOneVendorPage() {
        schemaWithTables(156);

        DataFlowTablePage page = service.schemaTables(10L, SCHEMA, 2, 10, null);

        assertThat(names(page)).isEqualTo(expectedNames(10, 20));
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(156);
        assertThat(page.totalPages()).isEqualTo(16);
        assertThat(page.searchCapped()).isFalse();
        verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, 1, 100);
        verify(apiClient, never()).schemaTables(CREDENTIAL, SCHEMA, 2, 100);
    }

    @Test
    @DisplayName("UI page 4 of size 30 crosses vendor pages 1 and 2")
    void crossesTwoVendorPages() {
        schemaWithTables(156);

        DataFlowTablePage page = service.schemaTables(10L, SCHEMA, 4, 30, null);

        assertThat(names(page)).isEqualTo(expectedNames(90, 120));
        verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, 1, 100);
        verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, 2, 100);
    }

    @Test
    @DisplayName("a slice that runs past the last vendor page is partial: 95 rows, page 4 of 30 gives rows 91-95")
    void stopsAtTheLastVendorPage() {
        schemaWithTables(95);

        DataFlowTablePage page = service.schemaTables(10L, SCHEMA, 4, 30, null);

        assertThat(names(page)).isEqualTo(expectedNames(90, 95));
        assertThat(page.total()).isEqualTo(95);
        assertThat(page.totalPages()).isEqualTo(4);
        verify(apiClient, never()).schemaTables(CREDENTIAL, SCHEMA, 2, 100);
    }

    @Test
    @DisplayName("pageSize=30&page=334 reads vendor page 100 only and never asks for page 101")
    void neverPassesTheVendorCeiling() {
        schemaWithTables(10_500);

        DataFlowTablePage page = service.schemaTables(10L, SCHEMA, 334, 30, null);

        assertThat(names(page)).isEqualTo(expectedNames(9_990, 10_000));
        assertThat(page.total()).isEqualTo(10_500);
        assertThat(page.totalPages()).isEqualTo(350);
        verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, 100, 100);
        verify(apiClient, never()).schemaTables(CREDENTIAL, SCHEMA, 101, 100);
    }

    @Test
    @DisplayName("each vendor page is cached for 5 minutes on its own, so pages inside it cost nothing")
    void cachesEachVendorPage() {
        schemaWithTables(156);

        service.schemaTables(10L, SCHEMA, 1, 10, null);
        service.schemaTables(10L, SCHEMA, 2, 10, null);
        service.schemaTables(10L, SCHEMA, 11, 10, null);

        verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, 1, 100);
        verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, 2, 100);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key).containsExactly(
                "dspm:dsdftables:10:s:" + SCHEMA + ":1", "dspm:dsdftables:10:s:" + SCHEMA + ":2");
        assertThat(cache.puts).allSatisfy(put -> assertThat(put.minutes()).isEqualTo(5));
    }

    // ── search ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a search loads every vendor page and matches names case-insensitively")
    void searchLoadsEveryPage() {
        schemaWithTables(250);

        DataFlowTablePage page = service.schemaTables(10L, SCHEMA, 1, 10, "AGENT_events");

        assertThat(page.total()).isEqualTo(25);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.searchCapped()).isFalse();
        assertThat(names(page)).hasSize(10).allMatch(name -> name.startsWith("Agent_Events_"));
        assertThat(names(page).get(0)).isEqualTo("Agent_Events_0");
        for (int vendorPage = 1; vendorPage <= 3; vendorPage++) {
            verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, vendorPage, 100);
        }
    }

    @Test
    @DisplayName("a search reuses vendor pages already cached")
    void searchReusesCachedPages() {
        schemaWithTables(250);

        service.schemaTables(10L, SCHEMA, 1, 10, null);
        DataFlowTablePage page = service.schemaTables(10L, SCHEMA, 3, 10, "agent");

        assertThat(names(page)).isEqualTo(List.of("Agent_Events_200", "Agent_Events_210", "Agent_Events_220",
                "Agent_Events_230", "Agent_Events_240"));
        verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, 1, 100);
        verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, 2, 100);
        verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, 3, 100);
    }

    @Test
    @DisplayName("a search stops at 100 vendor pages and reports searchCapped")
    void searchIsCapped() {
        schemaWithTables(15_000);

        DataFlowTablePage page = service.schemaTables(10L, SCHEMA, 1, 10, "agent");

        assertThat(page.searchCapped()).isTrue();
        assertThat(page.total()).isEqualTo(1_000);
        verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, 100, 100);
        verify(apiClient, never()).schemaTables(CREDENTIAL, SCHEMA, 101, 100);
    }

    @Test
    @DisplayName("a search shorter than 3 characters is ignored: plain paging, one vendor page")
    void shortSearchIsIgnored() {
        schemaWithTables(156);

        DataFlowTablePage page = service.schemaTables(10L, SCHEMA, 1, 10, DatastoreParams.dataFlowSearch("ag"));

        assertThat(page.total()).isEqualTo(156);
        assertThat(names(page)).isEqualTo(expectedNames(0, 10));
        verify(apiClient, times(1)).schemaTables(CREDENTIAL, SCHEMA, 1, 100);
        verify(apiClient, never()).schemaTables(CREDENTIAL, SCHEMA, 2, 100);
    }

    // ── schema-less tables and columns ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a schema-less database's tables come from the database route, cached under the d: key")
    void schemalessTables() {
        when(apiClient.schemaDatabaseTables(eq(CREDENTIAL), eq(DATABASE), anyInt(), eq(100)))
                .thenAnswer(inv -> tablePage(inv.getArgument(2), 30));

        DataFlowTablePage page = service.databaseTables(10L, DATABASE, 1, 10, null);

        assertThat(names(page)).isEqualTo(expectedNames(0, 10));
        assertThat(page.total()).isEqualTo(30);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key)
                .containsExactly("dspm:dsdftables:10:d:" + DATABASE + ":1");
        verify(apiClient, never()).schemaTables(eq(CREDENTIAL), eq(DATABASE), anyInt(), anyInt());
    }

    @Test
    @DisplayName("columns use the same vendor-page slicing and their own cache key")
    void columnsArePagedTheSameWay() {
        when(apiClient.schemaColumns(eq(CREDENTIAL), eq(TABLE), anyInt(), eq(100)))
                .thenAnswer(inv -> columnPage(inv.getArgument(2), 130));

        DataFlowColumnPage page = service.columns(10L, TABLE, 2, 10);
        DataFlowColumnPage crossing = service.columns(10L, TABLE, 4, 30);

        assertThat(page.rows()).extracting(DataFlowColumnPage.Column::name)
                .isEqualTo(IntStream.range(10, 20).mapToObj(i -> "col_" + i).toList());
        assertThat(page.total()).isEqualTo(130);
        assertThat(page.totalPages()).isEqualTo(13);
        assertThat(crossing.rows()).extracting(DataFlowColumnPage.Column::name)
                .isEqualTo(IntStream.range(90, 120).mapToObj(i -> "col_" + i).toList());
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key)
                .containsExactly("dspm:dsdfcols:10:" + TABLE + ":1", "dspm:dsdfcols:10:" + TABLE + ":2");
    }

    // ── trees ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the database tree stops at 10 vendor pages and reports truncated; MySQL is schema-less")
    void databaseTreeIsBounded() {
        when(apiClient.schemaDatabases(eq(CREDENTIAL), eq(SOURCE_ID), anyInt(), eq(100)))
                .thenAnswer(inv -> treePage(inv.getArgument(2), 12, inv.<Integer>getArgument(2) == 1 ? "MYSQL" : "POSTGRES"));

        DataFlowDatabases databases = service.databases(10L, SOURCE_ID);
        service.databases(10L, SOURCE_ID);

        assertThat(databases.items()).hasSize(10);
        assertThat(databases.truncated()).isTrue();
        assertThat(databases.items().get(0).schemaless()).isTrue();
        assertThat(databases.items().get(1).schemaless()).isFalse();
        verify(apiClient, times(1)).schemaDatabases(CREDENTIAL, SOURCE_ID, 10, 100);
        verify(apiClient, never()).schemaDatabases(CREDENTIAL, SOURCE_ID, 11, 100);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(tuple("dspm:dsdfdb:10:" + SOURCE_ID, 5));
    }

    @Test
    @DisplayName("a tree that fits reads every page and is not truncated")
    void smallTreesAreComplete() {
        when(apiClient.schemaDatabases(eq(CREDENTIAL), eq(SOURCE_ID), anyInt(), eq(100)))
                .thenAnswer(inv -> treePage(inv.getArgument(2), 2, "POSTGRES"));
        when(apiClient.schemaSchemas(eq(CREDENTIAL), eq(DATABASE), anyInt(), eq(100)))
                .thenAnswer(inv -> treePage(inv.getArgument(2), 1, null));

        DataFlowDatabases databases = service.databases(10L, SOURCE_ID);
        DataFlowSchemas schemas = service.schemas(10L, DATABASE);

        assertThat(databases.items()).hasSize(2);
        assertThat(databases.truncated()).isFalse();
        assertThat(schemas.items()).extracting(DataFlowSchemas.Schema::name).containsExactly("item_1");
        assertThat(schemas.truncated()).isFalse();
        verify(apiClient, never()).schemaDatabases(CREDENTIAL, SOURCE_ID, 3, 100);
        verify(apiClient, never()).schemaSchemas(CREDENTIAL, DATABASE, 2, 100);
    }

    @Test
    @DisplayName("the schema tree stops at 10 vendor pages and reports truncated")
    void schemaTreeIsBounded() {
        when(apiClient.schemaSchemas(eq(CREDENTIAL), eq(DATABASE), anyInt(), eq(100)))
                .thenAnswer(inv -> treePage(inv.getArgument(2), 11, null));

        DataFlowSchemas schemas = service.schemas(10L, DATABASE);

        assertThat(schemas.items()).hasSize(10);
        assertThat(schemas.truncated()).isTrue();
        verify(apiClient, never()).schemaSchemas(CREDENTIAL, DATABASE, 11, 100);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key)
                .containsExactly("dspm:dsdfschemas:10:" + DATABASE);
    }

    // ── Graph View ───────────────────────────────────────────────────────────────────────────────

    private static final String ERD_BODY = """
            {"nodes": [{"id": "t-1", "name": "invoice", "sensitive_count": 2, "row_count_estimate": 9},
                       {"id": "t-2", "name": "client"}],
             "edges": [{"foreign_key_id": "MARKER-FK", "constraint_name": "MARKER-CONSTRAINT",
                        "source_table_id": "t-1", "target_table_id": "t-2",
                        "columns": [{"source_column_name": "client_id", "target_column_name": "id"}]}]}
            """;

    private static final String RELATIONS_BODY = """
            {"outgoing": [{"constraint_name": "MARKER-CONSTRAINT", "other_schema_name": "public",
                           "other_table_name": "client",
                           "columns": [{"source_column_name": "client_id", "target_column_name": "id"}]}],
             "incoming": []}
            """;

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("schema and database diagrams are cached for 5 minutes under their s: and d: keys")
    void diagramsAreCached() {
        when(apiClient.schemaErd(CREDENTIAL, SCHEMA)).thenAnswer(inv -> parse(ERD_BODY));
        when(apiClient.databaseErd(CREDENTIAL, DATABASE)).thenAnswer(inv -> parse(ERD_BODY));

        DataFlowErd schemaErd = service.schemaErd(10L, SCHEMA);
        service.schemaErd(10L, SCHEMA);
        DataFlowErd databaseErd = service.databaseErd(10L, DATABASE);
        service.databaseErd(10L, DATABASE);

        verify(apiClient, times(1)).schemaErd(CREDENTIAL, SCHEMA);
        verify(apiClient, times(1)).databaseErd(CREDENTIAL, DATABASE);
        assertThat(schemaErd.nodes()).extracting(DataFlowErd.Node::name).containsExactly("invoice", "client");
        assertThat(schemaErd.edges()).extracting(DataFlowErd.Edge::id).containsExactly("fk-0");
        assertThat(databaseErd).isEqualTo(schemaErd);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes).containsExactly(
                tuple("dspm:dsdferd:10:s:" + SCHEMA, 5),
                tuple("dspm:dsdferd:10:d:" + DATABASE, 5));
        assertThat(cache.puts).allSatisfy(put -> assertThat(put.json()).doesNotContain("MARKER"));
    }

    @Test
    @DisplayName("a table's relations are cached for 5 minutes under dsdfrel")
    void relationsAreCached() {
        when(apiClient.tableRelations(CREDENTIAL, TABLE)).thenAnswer(inv -> parse(RELATIONS_BODY));

        DataFlowRelations relations = service.tableRelations(10L, TABLE);
        service.tableRelations(10L, TABLE);

        verify(apiClient, times(1)).tableRelations(CREDENTIAL, TABLE);
        assertThat(relations.outgoing()).extracting(DataFlowRelations.Relation::tableName).containsExactly("client");
        assertThat(relations.incoming()).isEmpty();
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(tuple("dspm:dsdfrel:10:" + TABLE, 5));
        assertThat(cache.puts).allSatisfy(put -> assertThat(put.json()).doesNotContain("MARKER"));
    }

    // ── order of checks ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the credential is resolved before any cache read; NOT_ENTITLED stops before the cache")
    void credentialFirst() {
        schemaWithTables(10);
        service.schemaTables(10L, SCHEMA, 1, 10, null);
        assertThat(events).startsWith("resolve", "get dspm:dsdftables:10:s:" + SCHEMA + ":1");
        when(apiClient.schemaErd(CREDENTIAL, SCHEMA)).thenAnswer(inv -> parse(ERD_BODY));
        when(apiClient.databaseErd(CREDENTIAL, DATABASE)).thenAnswer(inv -> parse(ERD_BODY));
        when(apiClient.tableRelations(CREDENTIAL, TABLE)).thenAnswer(inv -> parse(RELATIONS_BODY));
        events.clear();
        service.schemaErd(10L, SCHEMA);
        assertThat(events).startsWith("resolve", "get dspm:dsdferd:10:s:" + SCHEMA);
        events.clear();
        service.databaseErd(10L, DATABASE);
        assertThat(events).startsWith("resolve", "get dspm:dsdferd:10:d:" + DATABASE);
        events.clear();
        service.tableRelations(10L, TABLE);
        assertThat(events).startsWith("resolve", "get dspm:dsdfrel:10:" + TABLE);

        doThrow(new MattersApiException(MattersApiException.Reason.NOT_ENTITLED, "not entitled"))
                .when(resolver).resolve(10L);
        cache.gets.clear();
        MattersApiClient untouched = mock(MattersApiClient.class);
        DatastoreDataFlowService guarded = new DatastoreDataFlowService(resolver, untouched, new DataFlowMapper(), cache);

        assertThatThrownBy(() -> guarded.databases(10L, SOURCE_ID)).isInstanceOf(MattersApiException.class);
        assertThatThrownBy(() -> guarded.schemas(10L, DATABASE)).isInstanceOf(MattersApiException.class);
        assertThatThrownBy(() -> guarded.schemaTables(10L, SCHEMA, 1, 10, "agent"))
                .isInstanceOf(MattersApiException.class);
        assertThatThrownBy(() -> guarded.databaseTables(10L, DATABASE, 1, 10, null))
                .isInstanceOf(MattersApiException.class);
        assertThatThrownBy(() -> guarded.columns(10L, TABLE, 1, 10)).isInstanceOf(MattersApiException.class);
        assertThatThrownBy(() -> guarded.schemaErd(10L, SCHEMA)).isInstanceOf(MattersApiException.class);
        assertThatThrownBy(() -> guarded.databaseErd(10L, DATABASE)).isInstanceOf(MattersApiException.class);
        assertThatThrownBy(() -> guarded.tableRelations(10L, TABLE)).isInstanceOf(MattersApiException.class);
        verifyNoInteractions(untouched);
        assertThat(cache.gets).isEmpty();
    }
}
