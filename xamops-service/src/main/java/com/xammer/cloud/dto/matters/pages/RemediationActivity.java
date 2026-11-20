package com.xammer.cloud.dto.matters.pages;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The Remediation page (design 5.4): the summary cards, status counts and one page of runs.
 *
 * @param statusCounts upper-snake status code → count
 * @param skip         the request's row offset
 * @param limit        the request's page size
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RemediationActivity(Summary summary, Map<String, Long> statusCounts, long total, int skip, int limit,
                                  List<Item> items) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(long total, long waitingApproval, long inProgressScheduled, long completed, long failed) {
    }

    /**
     * @param createdAt   epoch seconds
     * @param scheduledAt epoch seconds
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(String id, String actionType, String actionLabel, String objectName, String objectPath,
                       String datastoreType, String datastoreLabel, String sourceType, Person initiatedBy,
                       String triggerType, String triggerLabel, Long createdAt, String status, String statusLabel,
                       List<String> approvers, Long scheduledAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Person(String name, String email) {
    }
}
