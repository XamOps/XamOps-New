package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.xampulse.dto.XpForecast;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Disk-full forecast by ordinary least squares on daily utilisation (percent). A forecast is only reported when the
 * trend is trustworthy: at least 5 points and R-squared at least 0.6. Otherwise the answer is "no reliable trend",
 * never an invented date.
 */
@Service("xpForecastService")
public class ForecastService {

    public static final double MIN_R2 = 0.6;
    public static final int MIN_POINTS = 5;

    /** @param dayIndex days since the first point; @param pct utilisation in percent (0..100) */
    public record Point(double dayIndex, double pct) {}

    public XpForecast forecast(Long accountId, String instanceId, String resource, List<Point> points) {
        String metric = "system.filesystem.utilization";
        if (points == null || points.size() < MIN_POINTS) {
            return new XpForecast(accountId, instanceId, resource, metric, null, null, 0, "Not enough history yet to forecast.");
        }
        int n = points.size();
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (Point p : points) {
            sx += p.dayIndex();
            sy += p.pct();
            sxx += p.dayIndex() * p.dayIndex();
            sxy += p.dayIndex() * p.pct();
        }
        double denom = n * sxx - sx * sx;
        if (Math.abs(denom) < 1e-12) {
            return new XpForecast(accountId, instanceId, resource, metric, null, null, 0, "No reliable trend.");
        }
        double slope = (n * sxy - sx * sy) / denom;
        double intercept = (sy - slope * sx) / n;
        double meanY = sy / n;
        double ssTot = 0, ssRes = 0;
        for (Point p : points) {
            double fit = intercept + slope * p.dayIndex();
            ssRes += (p.pct() - fit) * (p.pct() - fit);
            ssTot += (p.pct() - meanY) * (p.pct() - meanY);
        }
        double r2 = ssTot < 1e-12 ? 0 : 1 - ssRes / ssTot;
        if (r2 < MIN_R2) {
            return new XpForecast(accountId, instanceId, resource, metric, null, null, r2, "No reliable trend.");
        }
        if (slope <= 1e-6) {
            return new XpForecast(accountId, instanceId, resource, metric, null, null, r2,
                    "Usage is flat or falling. No fill date expected.");
        }
        double last = intercept + slope * points.get(n - 1).dayIndex();
        double days = Math.max(0, (100.0 - last) / slope);
        // 95% interval from the slope's standard error.
        double se = n > 2 ? Math.sqrt((ssRes / (n - 2)) / (sxx - sx * sx / n)) : 0;
        double slopeHi = slope + 1.96 * se;
        double slopeLo = Math.max(1e-6, slope - 1.96 * se);
        double lo = Math.max(0, (100.0 - last) / slopeHi);
        double hi = (100.0 - last) / slopeLo;
        String msg = String.format(Locale.ROOT, "%s on %s is %.0f%% full and rising %.1f points per day. About %.0f days to full.",
                resource, instanceId, last, slope, days);
        return new XpForecast(accountId, instanceId, resource, metric, days, List.of(lo, hi), r2, msg);
    }
}
