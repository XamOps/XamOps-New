package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The Endpoint drawer's Information totals (design 5.4 item 10).
 *
 * @param extensions     by count descending, then extension.
 * @param labelGroups    in API order.
 * @param fileCategories in API order.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointSummary(Long totalFiles, List<ExtensionCount> extensions, Long totalSensitives,
                              List<LabelGroup> labelGroups, List<CategoryGroup> fileCategories) {

    /** @param extension the chip icon. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ExtensionCount(String extension, String label, long count) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LabelGroup(String label, List<EntityCount> entities) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EntityCount(String name, long count) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CategoryGroup(String label, List<EntityCount> documentClasses) {
    }
}
