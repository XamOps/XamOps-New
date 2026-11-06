package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One row of the XamLens Datastores list (design 5.4 item 1), served inside the existing
 * {@code DspmPage<T>}.
 *
 * @param id       vendor uuid, for the old {@code /api/dspm/assets/{id}} detail (SaaS and endpoint rows).
 * @param sourceId the 24-hex {@code source_id}, the drawer's asset id.
 * @param agent    null unless {@code category = endpoint}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreRow(String id, String sourceId, String name, String category, String subtype,
                           String subtypeLabel, Risk risk, Sensitives sensitives, Source source, Scan scan,
                           Agent agent) {

    /**
     * @param score raw, never rounded on the server.
     * @param level CRITICAL, HIGH, MEDIUM, LOW or null; null is not folded to NONE (it renders "-").
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Risk(Double score, String level, String levelLabel) {
    }

    /** @param typeLabels display labels of {@code sensitives.types[]}, in API order. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sensitives(long recordCount, List<String> typeLabels) {
    }

    /** @param accountLabel filled by the account labeler; null renders "-". */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Source(String provider, String providerLabel, String accountLabel, String region) {
    }

    /** @param displayAt epoch seconds, chosen by {@code DatastoreRowMapper.scanDisplayAt}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Scan(String status, String statusLabel, Long displayAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Agent(String serialNumber, String status, String statusLabel, String version) {
    }
}
