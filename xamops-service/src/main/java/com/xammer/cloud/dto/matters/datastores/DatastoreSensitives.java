package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The Sensitive tab's Entity View and database Table View (design 5.4 item 6, rules in 5.6.5). Also the
 * {@code dssens} cache entry. Carries no sample.
 *
 * @param database true when the vendor sent a {@code database_level_response} object.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreSensitives(boolean database, List<Entity> entities, List<Database> databases) {

    /** @param blank the entity name is blank; at most one, always last. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Entity(String name, long count, String categoryLabel, boolean blank) {
    }

    /** @param tables count descending, ties in API order. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Database(String id, String name, List<Table> tables) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Table(String name, String schemaName, long count, List<String> labels, boolean expandable,
                        List<ColumnFinding> findings) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ColumnFinding(String columnName, String entityType, List<String> labels, long count,
                                boolean falsePositive) {
    }
}
