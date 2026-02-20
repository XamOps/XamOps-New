package com.xammer.billops.dto;

import lombok.Data;
import java.util.List;

@Data
public class RcaRequestDto {
    private Long ticketId;
    private String problemStatement;
    private String conclusion;
    
    // Dynamic sections
    private List<RcaSectionDto> incidentOverview;
    private List<RcaSectionDto> descriptionSections;
    private List<RcaSectionDto> rootCauseSections;
    private List<RcaSectionDto> resolutionSections;
    private List<RcaSectionDto> preventionSections;

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public String getProblemStatement() { return problemStatement; }
    public void setProblemStatement(String problemStatement) { this.problemStatement = problemStatement; }
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public List<RcaSectionDto> getIncidentOverview() { return incidentOverview; }
    public void setIncidentOverview(List<RcaSectionDto> incidentOverview) { this.incidentOverview = incidentOverview; }
    public List<RcaSectionDto> getDescriptionSections() { return descriptionSections; }
    public void setDescriptionSections(List<RcaSectionDto> descriptionSections) { this.descriptionSections = descriptionSections; }
    public List<RcaSectionDto> getRootCauseSections() { return rootCauseSections; }
    public void setRootCauseSections(List<RcaSectionDto> rootCauseSections) { this.rootCauseSections = rootCauseSections; }
    public List<RcaSectionDto> getResolutionSections() { return resolutionSections; }
    public void setResolutionSections(List<RcaSectionDto> resolutionSections) { this.resolutionSections = resolutionSections; }
    public List<RcaSectionDto> getPreventionSections() { return preventionSections; }
    public void setPreventionSections(List<RcaSectionDto> preventionSections) { this.preventionSections = preventionSections; }

    public static class RcaSectionDto {
        private String heading; // e.g., "Symptoms", "Impact"
        private String content; // The text content

        public String getHeading() { return heading; }
        public void setHeading(String heading) { this.heading = heading; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}