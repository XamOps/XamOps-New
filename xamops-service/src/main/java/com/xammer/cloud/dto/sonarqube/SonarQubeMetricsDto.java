package com.xammer.cloud.dto.sonarqube;

import lombok.Data;

/**
 * DTO to hold the key metrics retrieved from the SonarQube /api/measures/component endpoint.
 */
@Data
public class SonarQubeMetricsDto {
    private String qualityGateStatus; // e.g., "OK", "ERROR"
    private int bugs;
    private int vulnerabilities;
    private int codeSmells;
    private double coverage;
    private int linesOfCode;
}