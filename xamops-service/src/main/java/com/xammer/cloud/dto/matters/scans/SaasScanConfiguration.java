package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A SaaS integration's scan configuration (design 5.4), whitelisted field by field.
 *
 * @param samplingPercentage 0..100, else null
 * @param lastSavedAt        epoch seconds
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasScanConfiguration(String integrationId, boolean realtimeEnabled, boolean backfillingEnabled,
                                    Integer samplingPercentage, boolean provisioningComplete, Long lastSavedAt,
                                    String lastSavedBy, List<Scope> scopes) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Scope(String driveType, String driveLabel, String scopeType, Long totalActiveUsers,
                        List<String> supportedDomains) {
    }
}
