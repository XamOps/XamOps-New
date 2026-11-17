package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DataFlowColumnPage;
import com.xammer.cloud.dto.matters.datastores.DataFlowDatabases;
import com.xammer.cloud.dto.matters.datastores.DataFlowSchemas;
import com.xammer.cloud.dto.matters.datastores.DataFlowTablePage;
import com.xammer.cloud.dto.matters.datastores.DataFlowVendorPage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOr;
import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOrNull;
import static com.xammer.cloud.service.matters.DatastoreRowMapper.text;

/**
 * Field mapping for the Data Flow routes ({@code /api/v1/data/security/database-schema/…}), which answer
 * bare {@code {data[], pagination{page, page_size, total, total_pages}}} objects (design 5.6.10). The
 * paging arithmetic over vendor pages lives in the Data Flow service. Index, constraint,
 * {@code table_type}, {@code logical_type}, {@code is_primary_key} and
 * {@code foreign_key_constraint_names} are dropped.
 */
@Component
public class DataFlowMapper {

    /** Engines whose tables hang directly off the database (the vendor doc's schema-less engines). */
    private static final Set<String> SCHEMALESS_ENGINES = Set.of("MYSQL", "MARIADB");

    public List<DataFlowDatabases.Database> toDatabases(JsonNode body) {
        List<DataFlowDatabases.Database> items = new ArrayList<>();
        for (JsonNode node : body.path("data")) {
            String engine = text(node, "engine");
            items.add(new DataFlowDatabases.Database(text(node, "id"), text(node, "name"), engine,
                    engine != null && SCHEMALESS_ENGINES.contains(engine.toUpperCase(Locale.ROOT))));
        }
        return items;
    }

    public List<DataFlowSchemas.Schema> toSchemas(JsonNode body) {
        List<DataFlowSchemas.Schema> items = new ArrayList<>();
        for (JsonNode node : body.path("data")) {
            items.add(new DataFlowSchemas.Schema(text(node, "id"), text(node, "name")));
        }
        return items;
    }

    /** One vendor page of tables; {@code foreign_key_count} falls back to {@code foreign_keys_count}. */
    public DataFlowVendorPage<DataFlowTablePage.Table> toTablePage(JsonNode body) {
        List<DataFlowTablePage.Table> rows = new ArrayList<>();
        for (JsonNode node : body.path("data")) {
            Long foreignKeys = longOrNull(node, "foreign_key_count");
            rows.add(new DataFlowTablePage.Table(text(node, "id"), text(node, "name"),
                    longOrNull(node, "row_count_estimate"),
                    foreignKeys != null ? foreignKeys : longOrNull(node, "foreign_keys_count"),
                    longOrNull(node, "sensitive_count")));
        }
        return new DataFlowVendorPage<>(rows, vendorTotal(body), vendorTotalPages(body));
    }

    /** One vendor page of columns; {@code dataElements} = {@code data_elements[].name}. */
    public DataFlowVendorPage<DataFlowColumnPage.Column> toColumnPage(JsonNode body) {
        List<DataFlowColumnPage.Column> rows = new ArrayList<>();
        for (JsonNode node : body.path("data")) {
            List<String> elements = new ArrayList<>();
            for (JsonNode element : node.path("data_elements")) {
                String name = text(element, "name");
                if (name != null) {
                    elements.add(name);
                }
            }
            rows.add(new DataFlowColumnPage.Column(text(node, "id"), text(node, "name"), text(node, "native_type"),
                    longOrNull(node, "sensitive_count"), elements));
        }
        return new DataFlowVendorPage<>(rows, vendorTotal(body), vendorTotalPages(body));
    }

    /** {@code pagination.total}, 0 when missing. */
    public long vendorTotal(JsonNode body) {
        return longOr(body.path("pagination"), "total", 0L);
    }

    /** {@code pagination.total_pages}, 0 when missing. */
    public int vendorTotalPages(JsonNode body) {
        return (int) longOr(body.path("pagination"), "total_pages", 0L);
    }
}
