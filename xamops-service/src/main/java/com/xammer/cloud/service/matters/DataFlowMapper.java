package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DataFlowColumnPage;
import com.xammer.cloud.dto.matters.datastores.DataFlowDatabases;
import com.xammer.cloud.dto.matters.datastores.DataFlowErd;
import com.xammer.cloud.dto.matters.datastores.DataFlowRelations;
import com.xammer.cloud.dto.matters.datastores.DataFlowSchemas;
import com.xammer.cloud.dto.matters.datastores.DataFlowTablePage;
import com.xammer.cloud.dto.matters.datastores.DataFlowVendorPage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOr;
import static com.xammer.cloud.service.matters.DatastoreRowMapper.longOrNull;
import static com.xammer.cloud.service.matters.DatastoreRowMapper.text;

/**
 * Field mapping for the Data Flow routes ({@code /api/v1/data/security/database-schema/…}), which answer
 * bare {@code {data[], pagination{page, page_size, total, total_pages}}} objects (design 5.6.10). The
 * paging arithmetic over vendor pages lives in the Data Flow service. Index, constraint,
 * {@code table_type} and {@code logical_type} are dropped; a column's {@code is_primary_key} and
 * {@code foreign_key_constraint_names} become its {@code primaryKey} and {@code foreignKey} flags, and the
 * constraint names themselves are not returned (build 2 design 5.6.4). The schema diagram and a table's
 * relations ({@link #toErd}, {@link #toRelations}) read their text through {@link DrawerJson}.
 */
@Component
public class DataFlowMapper {

    /** Engines whose tables hang directly off the database (the vendor doc's schema-less engines). */
    private static final Set<String> SCHEMALESS_ENGINES = Set.of("MYSQL", "MARIADB");

    static final int MAX_ERD_NODES = 1_000;
    static final int MAX_ERD_EDGES = 3_000;
    static final int MAX_KEY_COLUMNS = 50;
    static final int MAX_COLUMN_PAIRS = 32;
    static final int MAX_RELATIONS = 200;

    /** A diagram node while its key columns are collected. */
    private static final class NodeBuilder {
        private final String id;
        private final String name;
        private final Long sensitiveCount;
        private final Set<String> keyColumns = new LinkedHashSet<>();

        private NodeBuilder(String id, String name, Long sensitiveCount) {
            this.id = id;
            this.name = name;
            this.sensitiveCount = sensitiveCount;
        }

        /** Adds a column name unless it is listed already or the node has 50. */
        private void addKeyColumn(String column) {
            if (column != null && keyColumns.size() < MAX_KEY_COLUMNS) {
                keyColumns.add(column);
            }
        }

        private DataFlowErd.Node build() {
            return new DataFlowErd.Node(id, name, sensitiveCount, List.copyOf(keyColumns));
        }
    }

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

    /**
     * One vendor page of columns; {@code dataElements} = {@code data_elements[].name}. {@code primaryKey} is
     * {@code is_primary_key} (false when absent); {@code foreignKey} is true for a non-empty
     * {@code foreign_key_constraint_names}.
     */
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
            JsonNode primaryKey = node.path("is_primary_key");
            JsonNode constraints = node.path("foreign_key_constraint_names");
            rows.add(new DataFlowColumnPage.Column(text(node, "id"), text(node, "name"), text(node, "native_type"),
                    longOrNull(node, "sensitive_count"), elements,
                    primaryKey.isBoolean() && primaryKey.booleanValue(),
                    constraints.isArray() && constraints.size() > 0));
        }
        return new DataFlowVendorPage<>(rows, vendorTotal(body), vendorTotalPages(body));
    }

    /**
     * The schema diagram, from {@code …/schemas/{id}/erd} or, for schema-less engines,
     * {@code …/databases/{id}/erd} (design 5.6.4).
     *
     * <ul>
     *   <li>Nodes: the first 1,000 in API order with a text id; {@code nodesTruncated} when more arrived.</li>
     *   <li>Edges: of the first 3,000 ({@code edgesTruncated} when more arrived), those whose source is a kept
     *       node. A target that is a kept node makes the edge internal; any other target is external, keyed
     *       {@code ext:<schema>.<table>} with one {@link DataFlowErd.ExternalTable} per key, and an external
     *       target without both names is dropped. Edge ids are {@code fk-<index>}, the edge's position in
     *       the vendor's list, so the vendor's {@code foreign_key_id} is not returned. Edges dropped because
     *       their source fell outside the node cap do not set {@code edgesTruncated}.</li>
     *   <li>Key columns (the FK chips): walking the kept edges in API order, each edge adds its
     *       {@code source_column_name}s to its source node and, when internal, its
     *       {@code target_column_name}s to its target node; a name already listed is skipped, so the chips
     *       keep edge order. On the local {@code invoice} shape, whose first edge is outgoing, this gives
     *       {@code cloud_account_id, id, client_id}, the console's order (eleventh batch).</li>
     * </ul>
     *
     * <p><b>[O3], review fix R17:</b> on a card whose first edge is incoming (the prod {@code invoice} shape),
     * edge order gives {@code id, client_id, cloud_account_id}; whether the console orders that card the same
     * way is unverified. The final report lists it among the open O3 points.
     *
     * <p>The column pairs and {@code constraint_name} are read for the key columns only and not returned;
     * {@code table_type}, {@code row_count_estimate}, {@code foreign_key_count}, {@code index_count} and
     * {@code unique_constraint_count} are not read.
     */
    public DataFlowErd toErd(JsonNode body) {
        JsonNode nodesJson = body.path("nodes");
        JsonNode edgesJson = body.path("edges");

        Map<String, NodeBuilder> nodes = new LinkedHashMap<>();
        int nodeIndex = 0;
        for (JsonNode node : items(nodesJson)) {
            if (nodeIndex++ >= MAX_ERD_NODES) {
                break;
            }
            String id = DrawerJson.string(node, "id");
            if (id != null && !nodes.containsKey(id)) {
                nodes.put(id, new NodeBuilder(id, DrawerJson.string(node, "name"), longOrNull(node, "sensitive_count")));
            }
        }

        List<DataFlowErd.Edge> edges = new ArrayList<>();
        Map<String, DataFlowErd.ExternalTable> externalTables = new LinkedHashMap<>();
        int edgeIndex = 0;
        for (JsonNode edge : items(edgesJson)) {
            int position = edgeIndex++;
            if (position >= MAX_ERD_EDGES) {
                break;
            }
            String sourceId = DrawerJson.string(edge, "source_table_id");
            NodeBuilder source = sourceId == null ? null : nodes.get(sourceId);
            if (source == null) {
                continue;
            }
            String targetId = DrawerJson.string(edge, "target_table_id");
            NodeBuilder target = targetId == null ? null : nodes.get(targetId);
            String targetKey;
            if (target != null) {
                targetKey = targetId;
            } else {
                String schemaName = DrawerJson.string(edge, "target_schema_name");
                String tableName = DrawerJson.string(edge, "target_table_name");
                if (schemaName == null || tableName == null) {
                    continue;
                }
                targetKey = "ext:" + schemaName + "." + tableName;
                externalTables.putIfAbsent(targetKey, new DataFlowErd.ExternalTable(targetKey, schemaName, tableName));
            }
            edges.add(new DataFlowErd.Edge("fk-" + position, sourceId, targetKey, target == null));

            int pairs = 0;
            for (JsonNode pair : items(edge.path("columns"))) {
                if (pairs++ >= MAX_COLUMN_PAIRS) {
                    break;
                }
                source.addKeyColumn(DrawerJson.string(pair, "source_column_name"));
                if (target != null) {
                    target.addKeyColumn(DrawerJson.string(pair, "target_column_name"));
                }
            }
        }

        return new DataFlowErd(nodes.values().stream().map(NodeBuilder::build).toList(), edges,
                List.copyOf(externalTables.values()),
                nodesJson.isArray() && nodesJson.size() > MAX_ERD_NODES,
                edgesJson.isArray() && edgesJson.size() > MAX_ERD_EDGES);
    }

    /**
     * A table's keys for the Relationships tab ({@code …/tables/{id}/relations}): {@code outgoing[]} and
     * {@code incoming[]}, 200 each in API order, each with up to 32 column pairs. An item without a table
     * name is dropped. {@code foreign_key_id}, {@code constraint_name}, {@code other_table_id},
     * {@code other_database_name} and {@code table_id} are not returned.
     */
    public DataFlowRelations toRelations(JsonNode body) {
        return new DataFlowRelations(relations(body.path("outgoing")), relations(body.path("incoming")));
    }

    private static List<DataFlowRelations.Relation> relations(JsonNode array) {
        List<DataFlowRelations.Relation> out = new ArrayList<>();
        for (JsonNode item : items(array)) {
            if (out.size() >= MAX_RELATIONS) {
                break;
            }
            String tableName = DrawerJson.string(item, "other_table_name");
            if (tableName == null) {
                continue;
            }
            List<DataFlowRelations.ColumnPair> columns = new ArrayList<>();
            for (JsonNode pair : items(item.path("columns"))) {
                if (columns.size() >= MAX_COLUMN_PAIRS) {
                    break;
                }
                columns.add(new DataFlowRelations.ColumnPair(DrawerJson.string(pair, "source_column_name"),
                        DrawerJson.string(pair, "target_column_name")));
            }
            out.add(new DataFlowRelations.Relation(DrawerJson.string(item, "other_schema_name"), tableName, columns));
        }
        return out;
    }

    /** The elements of an array; nothing for any other node. */
    private static Iterable<JsonNode> items(JsonNode array) {
        return array.isArray() ? array : List.of();
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
