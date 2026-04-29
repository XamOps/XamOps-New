package com.xammer.cloud.dto.sre;

import com.xammer.cloud.domain.SreInvestigation;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class SreInvestigationDto {
    private Long id;
    private Long clientId;
    private Long cloudAccountId;
    private SreInvestigation.TriggerType triggerType;
    private SreInvestigation.ResourceType resourceType;
    private String resourceId;
    private String resourceName;
    private SreInvestigation.InvestigationStatus status;
    private String reportSummary;
    private String reportRootCause;
    private List<String> reportRecommendations;
    private String reportFullMarkdown;
    private String errorMessage;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private String verdict;
    private String verdictAt;
    private String verdictNote;

    public static SreInvestigationDto from(SreInvestigation inv) {
        SreInvestigationDto dto = new SreInvestigationDto();
        dto.setId(inv.getId());
        dto.setClientId(inv.getClientId());
        dto.setCloudAccountId(inv.getCloudAccountId());
        dto.setTriggerType(inv.getTriggerType());
        dto.setResourceType(inv.getResourceType());
        dto.setResourceId(inv.getResourceId());
        dto.setResourceName(inv.getResourceName());
        dto.setStatus(inv.getStatus());
        dto.setReportSummary(inv.getReportSummary());
        dto.setReportRootCause(inv.getReportRootCause());
        dto.setReportFullMarkdown(inv.getReportFullMarkdown());
        dto.setErrorMessage(inv.getErrorMessage());
        dto.setCreatedAt(inv.getCreatedAt());
        dto.setStartedAt(inv.getStartedAt());
        dto.setCompletedAt(inv.getCompletedAt());

        dto.setVerdict(inv.getVerdict() != null ? inv.getVerdict().name() : null);
        dto.setVerdictAt(inv.getVerdictAt() != null ? inv.getVerdictAt().toString() : null);
        dto.setVerdictNote(inv.getVerdictNote());

        if (inv.getReportRecommendations() != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                dto.setReportRecommendations(mapper.readValue(inv.getReportRecommendations(),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            } catch (Exception e) {
                dto.setReportRecommendations(List.of());
            }
        }
        return dto;
    }
}
