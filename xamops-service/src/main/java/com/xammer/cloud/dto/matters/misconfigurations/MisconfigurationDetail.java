package com.xammer.cloud.dto.matters.misconfigurations;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;

import java.util.List;

/**
 * One policy of the Misconfigurations page, for its drawer: Details, Remediation and Affected Datastores
 * (build 5, plan 1.4). The text fields come from the policy's first finding in asset-list order.
 *
 * @param description markdown, raw.
 * @param remediation markdown, raw.
 * @param cli         the CLI fix, or null when the vendor sent none.
 * @param learnMore   an absolute http or https URL, or null.
 * @param affected    the datastores reporting the policy, each once, in asset-list order.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MisconfigurationDetail(String policyId, String title, String source, String severity,
                                     String severityLabel, String description, String remediation, String cli,
                                     String learnMore, List<String> frameworks, List<AffectedDatastore> affected) {

    /**
     * @param datastore     the Datastores list row, account-labelled, so it can open the datastore drawer.
     * @param lastScannedAt the asset's raw {@code scan_status.last_scanned_at} in epoch seconds, or null.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AffectedDatastore(DatastoreRow datastore, Long lastScannedAt) {
    }
}
