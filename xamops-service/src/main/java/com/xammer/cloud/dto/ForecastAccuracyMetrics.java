package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Aggregate accuracy metrics for ML cost forecasts.
 *
 * MAPE  = Mean Absolute Percentage Error (lower is better; the headline metric).
 * MAE   = Mean Absolute Error in currency units.
 * accuracyScore = max(0, 100 - MAPE), a friendly 0-100 score for the UI.
 * confidenceCoverage = % of actuals that fell inside the predicted [lower, upper] band
 *                      (a well-calibrated 80% interval should be ~80%).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForecastAccuracyMetrics {

    private String provider;
    private String accountId;
    private String serviceName; // "ALL" or specific service; null = aggregated across services

    private int evaluatedCount;     // records with a known actual
    private int pendingCount;       // predictions still awaiting their target day
    private int skippedCount;       // predictions where actual never arrived

    private Double mape;            // null if no evaluable (non-zero-actual) records
    private Double mae;             // mean absolute error in currency
    private Double accuracyScore;   // 0-100, null if mape null
    private Double confidenceCoverage; // 0-100

    private String dataQuality;     // "GOOD" | "FAIR" | "POOR" | "INSUFFICIENT_DATA"
    private String message;

    /** Optional breakdown of MAPE by forecast horizon bucket (e.g. "1-7d", "8-30d"). */
    private Map<String, Double> mapeByHorizon;

    /** Empty/insufficient-data result helper. */
    public static ForecastAccuracyMetrics insufficient(String provider, String accountId, String serviceName,
                                                        int pendingCount) {
        ForecastAccuracyMetrics m = new ForecastAccuracyMetrics();
        m.provider = provider;
        m.accountId = accountId;
        m.serviceName = serviceName;
        m.evaluatedCount = 0;
        m.pendingCount = pendingCount;
        m.skippedCount = 0;
        m.dataQuality = "INSUFFICIENT_DATA";
        m.message = "Not enough evaluated forecasts yet. Accuracy appears after predicted "
                + "days have passed and actual costs are available (typically a few days).";
        return m;
    }
}
