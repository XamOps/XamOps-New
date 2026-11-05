package com.xammer.cloud.xampulse.service.insight;

import org.springframework.stereotype.Service;

/**
 * Anomaly test against a median/MAD baseline. Named with the XamPulse prefix because
 * {@code service/sre/AnomalyDetector} already exists in this application.
 */
@Service("xpAnomalyDetector")
public class XamPulseAnomalyDetector {

    /** Default band half-width in scaled MADs. */
    public static final double DEFAULT_K = 4.0;

    public boolean isAnomalous(double value, BaselineMath.Baseline baseline) {
        return BaselineMath.isAnomalous(value, baseline, DEFAULT_K);
    }

    public double[] band(BaselineMath.Baseline baseline) {
        return BaselineMath.band(baseline, DEFAULT_K);
    }
}
