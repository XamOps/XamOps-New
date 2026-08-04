package com.xammer.cloud.dto.war;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One control evaluated against one customer account: the advisory metadata plus
 * what WAR actually observed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarFinding {

    // --- Control identity (from the advisory) ---
    private String controlId;
    private int ordinal;
    private String category;
    private String subCategory;
    private String title;
    private String description;
    private String severity;
    private String tools;
    private String reference;
    private String controlType;
    private String businessImpact;
    private String impactLevel;
    private List<String> attackPaths;
    private List<String> attackPathLabels;

    // --- Observed state (from this account) ---
    private WarStatus status;
    private boolean automated;
    private String summary;
    private List<String> evidence;
    private int affectedCount;
    private int scannedCount;
    private String region;

    /**
     * IAM action that was denied when {@code status == UNKNOWN}. Lets the page say
     * "grant guardduty:ListDetectors" rather than leaving a silent gap.
     */
    private String deniedAction;

    /**
     * Weighted contribution this finding makes to the WAR score. Surfaced so the
     * "fix these three things first" ordering is explainable rather than magic.
     */
    private int riskWeight;
}
