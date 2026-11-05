package com.xammer.cloud.xampulse.service.alert;

import com.xammer.cloud.xampulse.dto.XpAlertPreview;
import com.xammer.cloud.xampulse.dto.XpFiring;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Dry-run of a threshold rule over supplied points: when would it have fired, and how many notifications. */
public final class AlertPreviewEngine {

    public record Point(Instant ts, double value) {}

    private AlertPreviewEngine() {}

    public static XpAlertPreview run(Map<String, List<Point>> seriesByGroup, String op, double threshold, Duration hold) {
        List<XpFiring> firings = new ArrayList<>();
        int evaluated = 0;
        for (Map.Entry<String, List<Point>> e : seriesByGroup.entrySet()) {
            Instant breachStart = null;
            Instant firingFrom = null;
            double peak = Double.NEGATIVE_INFINITY;
            Instant last = null;
            for (Point p : e.getValue()) {
                evaluated++;
                last = p.ts();
                if (compare(p.value(), op, threshold)) {
                    if (breachStart == null) {
                        breachStart = p.ts();
                        peak = p.value();
                    }
                    peak = Math.max(peak, p.value());
                    if (firingFrom == null && Duration.between(breachStart, p.ts()).compareTo(hold) >= 0) {
                        firingFrom = breachStart;
                    }
                } else {
                    if (firingFrom != null) {
                        firings.add(new XpFiring(firingFrom, p.ts(), Map.of("group", e.getKey()), peak));
                    }
                    breachStart = null;
                    firingFrom = null;
                    peak = Double.NEGATIVE_INFINITY;
                }
            }
            if (firingFrom != null) {
                firings.add(new XpFiring(firingFrom, last, Map.of("group", e.getKey()), peak));
            }
        }
        return new XpAlertPreview(firings, evaluated, firings.size());
    }

    public static boolean compare(double v, String op, double t) {
        return switch (op) {
            case ">" -> v > t;
            case ">=" -> v >= t;
            case "<" -> v < t;
            case "<=" -> v <= t;
            default -> throw new IllegalArgumentException("Unsupported operator");
        };
    }
}
