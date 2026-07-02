package com.xammer.cloud.dto.azure;

import java.util.List;

/**
 * Wrapper response for Azure Security data (Secure Score + Alerts + Assessments).
 * Includes a status field so the frontend can distinguish between
 * permission errors (403), empty results, and successful fetches.
 */
public class AzureSecurityResponse {

    public enum Status {
        OK,               // Successfully fetched data
        NO_DATA,          // 200 but no findings
        PERMISSION_ERROR, // 403 — Security Reader role required
        API_ERROR         // other HTTP error or exception
    }

    private Status status;
    private double secureScore;   // 0-100 (from Microsoft.Security/secureScores)
    private int maxScore;         // max possible score (usually 100)
    private List<AzureSecurityAlert> alerts;
    private List<AzureSecurityAssessment> assessments; // Unhealthy only
    private List<AzureSecureScoreControl> scoreControls; // per-control score breakdown

    public AzureSecurityResponse() {}

    public AzureSecurityResponse(Status status, double secureScore, int maxScore,
                                  List<AzureSecurityAlert> alerts,
                                  List<AzureSecurityAssessment> assessments) {
        this(status, secureScore, maxScore, alerts, assessments, new java.util.ArrayList<>());
    }

    public AzureSecurityResponse(Status status, double secureScore, int maxScore,
                                  List<AzureSecurityAlert> alerts,
                                  List<AzureSecurityAssessment> assessments,
                                  List<AzureSecureScoreControl> scoreControls) {
        this.status = status;
        this.secureScore = secureScore;
        this.maxScore = maxScore;
        this.alerts = alerts;
        this.assessments = assessments;
        this.scoreControls = scoreControls;
    }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public double getSecureScore() { return secureScore; }
    public void setSecureScore(double secureScore) { this.secureScore = secureScore; }

    public int getMaxScore() { return maxScore; }
    public void setMaxScore(int maxScore) { this.maxScore = maxScore; }

    public List<AzureSecurityAlert> getAlerts() { return alerts; }
    public void setAlerts(List<AzureSecurityAlert> alerts) { this.alerts = alerts; }

    public List<AzureSecurityAssessment> getAssessments() { return assessments; }
    public void setAssessments(List<AzureSecurityAssessment> assessments) { this.assessments = assessments; }

    public List<AzureSecureScoreControl> getScoreControls() { return scoreControls; }
    public void setScoreControls(List<AzureSecureScoreControl> scoreControls) { this.scoreControls = scoreControls; }
}
