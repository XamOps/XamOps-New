package com.xammer.cloud.dto.azure;

/**
 * DTO representing a single Microsoft Defender for Cloud secure score control.
 * Shows how each security control contributes to (or detracts from) the overall
 * secure score, so users know which controls to fix first for the biggest gain.
 *
 * Sourced from: GET .../providers/Microsoft.Security/secureScoreControls?api-version=2020-01-01-preview
 */
public class AzureSecureScoreControl {

    private String controlName;          // e.g. "Enable MFA", "Encrypt data in transit"
    private double currentScore;         // points currently earned
    private double maxScore;             // maximum points available for this control
    private double percentage;           // 0-100, how complete this control is
    private int healthyResourceCount;    // resources passing this control
    private int unhealthyResourceCount;  // resources failing this control (actionable)

    public AzureSecureScoreControl() {}

    public AzureSecureScoreControl(String controlName, double currentScore, double maxScore,
                                   double percentage, int healthyResourceCount, int unhealthyResourceCount) {
        this.controlName = controlName;
        this.currentScore = currentScore;
        this.maxScore = maxScore;
        this.percentage = percentage;
        this.healthyResourceCount = healthyResourceCount;
        this.unhealthyResourceCount = unhealthyResourceCount;
    }

    /** Points recoverable if this control is fully remediated. */
    public double getRecoverableScore() {
        return Math.max(0, maxScore - currentScore);
    }

    public String getControlName() { return controlName; }
    public void setControlName(String controlName) { this.controlName = controlName; }

    public double getCurrentScore() { return currentScore; }
    public void setCurrentScore(double currentScore) { this.currentScore = currentScore; }

    public double getMaxScore() { return maxScore; }
    public void setMaxScore(double maxScore) { this.maxScore = maxScore; }

    public double getPercentage() { return percentage; }
    public void setPercentage(double percentage) { this.percentage = percentage; }

    public int getHealthyResourceCount() { return healthyResourceCount; }
    public void setHealthyResourceCount(int healthyResourceCount) { this.healthyResourceCount = healthyResourceCount; }

    public int getUnhealthyResourceCount() { return unhealthyResourceCount; }
    public void setUnhealthyResourceCount(int unhealthyResourceCount) { this.unhealthyResourceCount = unhealthyResourceCount; }
}
