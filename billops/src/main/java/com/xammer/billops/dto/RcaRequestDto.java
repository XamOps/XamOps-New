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

    @Data
    public static class RcaSectionDto {
        private String heading; // e.g., "Symptoms", "Impact"
        private String content; // The text content
    }
}