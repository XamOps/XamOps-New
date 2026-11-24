package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The drawer header and Information tab (design 5.4 item 4, rules in 5.6.4). A whitelist: nothing from
 * {@code asset_details} except the validated {@link #consoleUrl}, and no sample.
 *
 * @param fileSummary            null when the vendor's {@code file_types} is null.
 * @param properties             region, datastore_size, then API order.
 * @param securityConfigurations null when the vendor's {@code security_configurations} is null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreOverview(String consoleUrl, String scanningStatus, String scanningStatusLabel,
                                boolean canDiscoverSchema, FileSummary fileSummary,
                                SensitiveRecords sensitiveRecords, List<Property> properties,
                                List<SecurityConfiguration> securityConfigurations) {

    /** @param types in display order. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FileSummary(Long totalFiles, List<FileType> types) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FileType(String key, String label, long count) {
    }

    /** @param groups in API order. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SensitiveRecords(Long total, List<LabelGroup> groups) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LabelGroup(String code, String label, List<EntityCount> entities) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EntityCount(String name, long count) {
    }

    /** @param bytes set only for {@code datastore_size}; {@code text} is null then. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Property(String key, String label, String text, Long bytes) {
    }

    /** @param value prettified: "Enabled", "Disabled", "Sse S3". */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SecurityConfiguration(String key, String label, String value) {
    }
}
