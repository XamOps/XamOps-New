package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a single AI-driven recommendation for GCP.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpAiRecommendationDto {
    private String category; // e.g., "COST_SAVING", "PERFORMANCE"
    private String description;
    private String action; // e.g., "Change machine type", "Delete unused disk"
    private String resource; // The resource the action applies to
    private double estimatedMonthlySavings;
}