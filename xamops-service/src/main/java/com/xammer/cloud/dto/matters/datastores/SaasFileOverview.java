package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The SaaS file drawer's header, cards, Sensitives and Details (design 5.4 item 5). The drawer is
 * opened by the list row's id, so the overview's own id is not returned.
 *
 * @param fileExtension  lower-cased: the header icon.
 * @param driveType      the card icon; GMAIL selects the Gmail lineage.
 * @param lastModifiedAt epoch seconds.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasFileOverview(String name, String fileExtension, String fileTypeLabel, String driveType,
                               String accountName, String owner, String categoryLabel, String documentClassLabel,
                               Long sizeBytes, Boolean structured, Long lastModifiedAt, String lastModifiedBy,
                               long sensitiveRecords, Long policyViolations, Sharing sharing,
                               List<Sensitive> sensitives, List<MetadataEntry> metadata) {

    /** No component starts with "is": Jackson would drop the prefix from the JSON name of a boolean. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sharing(List<String> internalEmails, List<String> externalEmails, List<String> internalGroups,
                          List<String> externalGroups, boolean privateShare, boolean publicShare, boolean orgWide,
                          boolean externalShare) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sensitive(String dataElement, String categoryLabel, long count) {
    }

    /** @param key the metadata key: the React key. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MetadataEntry(String key, String label, String value) {
    }
}
