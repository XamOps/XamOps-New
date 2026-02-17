package com.xammer.cloud.dto.autospotting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationDto {
    private Long id;
    private String title;
    private String description;
    private Double potentialSavings;
    private String priority; // "high", "medium", "low"
    private String asgName;
    private String region;
}
