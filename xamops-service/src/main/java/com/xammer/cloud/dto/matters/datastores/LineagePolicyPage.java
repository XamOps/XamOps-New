package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Step 1 of the Data Lineage page: one page of a category's policies (build 4 design 5.4, endpoint L3).
 * One exposure is one policy.
 *
 * @param available false while the exposure switch is off.
 * @param rows      in API order.
 * @param total     the category's count: the category node's chip and the pager.
 * @param skip      echoes the request.
 * @param limit     echoes the request.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LineagePolicyPage(boolean available, List<Policy> rows, long total, int skip, int limit) {

    /**
     * @param id            {@code ref_policy_id}, a lower-cased UUID; null: the node is not choosable.
     * @param severity      CRITICAL, HIGH, MEDIUM or LOW: the dot colour; null otherwise.
     * @param severityLabel the dot's text, such as "Medium".
     * @param service       {@code target_service} upper-cased: the node icon.
     * @param datastores    {@code total_exposures}: the "N datastores" chip.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Policy(String id, String name, String severity, String severityLabel, String service,
                         Long datastores) {
    }

    /** The switch is off. */
    public static LineagePolicyPage unavailable(int skip, int limit) {
        return new LineagePolicyPage(false, List.of(), 0, skip, limit);
    }

    /** No row can match, such as an Exclude of every option; no vendor call was made. */
    public static LineagePolicyPage empty(int skip, int limit) {
        return new LineagePolicyPage(true, List.of(), 0, skip, limit);
    }
}
