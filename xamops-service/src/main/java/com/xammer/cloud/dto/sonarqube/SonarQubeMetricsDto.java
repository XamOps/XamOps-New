package com.xammer.cloud.dto.sonarqube;

import lombok.Data;

/**
 * DTO to hold the key metrics retrieved from the SonarQube /api/measures/component endpoint.
 * This now includes all metrics from the UI screenshot.
 */
@Data
public class SonarQubeMetricsDto {
    // Quality Gate
    private String qualityGateStatus; // e.g., "OK", "ERROR"
    private int linesOfCode;

    // Reliability
    private int bugs;
    private String reliabilityRating; // A-E Rating

    // Security
    private int vulnerabilities;
    private String securityRating; // A-E Rating

    // Security Hotspots
    private int securityHotspots;
    private String securityReviewRating; // A-E Rating
    
    // Maintainability
    private int codeSmells;
    private String maintainabilityRating; // A-E Rating
    private int techDebt; // In minutes

    // Coverage
    private double coverage;
    private int linesToCover;

    // Duplications
    private int duplicatedLines;
    private double duplicationDensity; // Percentage
}