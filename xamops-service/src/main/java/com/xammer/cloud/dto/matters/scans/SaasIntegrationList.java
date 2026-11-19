package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The SaaS integrations of Scan Configurations, derived from the SaaS rows (design 4.6).
 *
 * @param partial the SaaS rows were read only in part
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasIntegrationList(List<SaasIntegration> integrations, boolean partial) {

    /** @param provider GOOGLE_WORKSPACE, SLACK, MICROSOFT_365, JIRA, CONFLUENCE or GITLAB */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SaasIntegration(String id, String provider, String accountLabel, List<String> driveTypes) {
    }
}
