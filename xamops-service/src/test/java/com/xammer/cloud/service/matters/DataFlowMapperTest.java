package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.DataFlowColumnPage;
import com.xammer.cloud.dto.matters.datastores.DataFlowDatabases;
import com.xammer.cloud.dto.matters.datastores.DataFlowSchemas;
import com.xammer.cloud.dto.matters.datastores.DataFlowTablePage;
import com.xammer.cloud.dto.matters.datastores.DataFlowVendorPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
                        java.util.List.of("EMAIL ADDRESS", "PERSON NAME")),
                new DataFlowColumnPage.Column("c-2", "id", "int8", 0L, java.util.List.of()));
        assertThat(page.total()).isEqualTo(13);
        String serialised = json.writeValueAsString(page);
        assertThat(serialised).doesNotContain("logical").doesNotContain("fk_x").doesNotContain("primary");
    }

    @Test
    void vendorTotalsDefaultToZero() {
        assertThat(mapper.vendorTotal(parse("{}"))).isZero();
        assertThat(mapper.vendorTotalPages(parse("{\"pagination\": {\"total_pages\": \"3\"}}"))).isZero();
        assertThat(mapper.vendorTotalPages(parse("{\"pagination\": {\"total_pages\": 3}}"))).isEqualTo(3);
    }
}
