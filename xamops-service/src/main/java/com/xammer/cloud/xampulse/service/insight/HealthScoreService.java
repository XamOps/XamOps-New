package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.xampulse.dto.XpHealth;
import com.xammer.cloud.xampulse.dto.XpLane;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Host health lanes and the 0-100 score (architecture section 6). Pure and deterministic.
 *
 * <p>Rule that matters most: missing data is never healthy. A lane with no signal is {@code NO_DATA}; a host with
 * no CPU, memory, disk or freshness signal at all has a {@code null} score, not 100.
 */
@Service("xpHealthScoreService")
public class HealthScoreService {

    /** What we know about a host right now. Any field may be null (no data). */
    public record Signals(Double cpuPct, Double memPct, Double diskPct, Long secondsSinceData, Double errorsPerMin) {}

    public record Result(Integer score, XpHealth health, List<XpLane> lanes, String reason) {}

    public Result evaluate(Signals s) {
        List<XpLane> lanes = new ArrayList<>();
        lanes.add(threshold("CPU", s.cpuPct(), 80, 95, "CPU at %.0f%%"));
        lanes.add(threshold("MEMORY", s.memPct(), 85, 95, "Memory at %.0f%%"));
        lanes.add(threshold("DISK", s.diskPct(), 80, 90, "Disk at %.0f%%"));
        lanes.add(new XpLane("NETWORK", XpHealth.NO_DATA, "No network health signal collected yet"));
        lanes.add(threshold("ERRORS", s.errorsPerMin(), 5, 50, "%.1f errors per minute"));
        lanes.add(freshness(s.secondsSinceData()));

        boolean anySignal = s.cpuPct() != null || s.memPct() != null || s.diskPct() != null || s.secondsSinceData() != null;
        if (!anySignal) {
            return new Result(null, XpHealth.NO_DATA, lanes, "No data received yet");
        }
        int score = 100;
        XpHealth worst = XpHealth.OK;
        String reason = null;
        for (XpLane l : lanes) {
            if (l.health() == XpHealth.CRIT) {
                score -= 30;
                if (worst != XpHealth.CRIT) {
                    worst = XpHealth.CRIT;
                    reason = l.reason();
                }
            } else if (l.health() == XpHealth.WARN) {
                score -= 10;
                if (worst == XpHealth.OK) {
                    worst = XpHealth.WARN;
                    reason = l.reason();
                }
            }
        }
        // Worst-of cap: a critical lane can never leave the host looking nearly healthy.
        if (worst == XpHealth.CRIT) {
            score = Math.min(score, 59);
        } else if (worst == XpHealth.WARN) {
            score = Math.min(score, 89);
        }
        return new Result(Math.max(0, score), worst, lanes, reason);
    }

    private static XpLane threshold(String lane, Double value, double warn, double crit, String fmt) {
        if (value == null || value.isNaN()) {
            return new XpLane(lane, XpHealth.NO_DATA, "No data");
        }
        String text = String.format(java.util.Locale.ROOT, fmt, value);
        if (value >= crit) {
            return new XpLane(lane, XpHealth.CRIT, text);
        }
        if (value >= warn) {
            return new XpLane(lane, XpHealth.WARN, text);
        }
        return new XpLane(lane, XpHealth.OK, text);
    }

    private static XpLane freshness(Long secondsSince) {
        if (secondsSince == null) {
            return new XpLane("FRESHNESS", XpHealth.NO_DATA, "No data received yet");
        }
        long min = secondsSince / 60;
        if (secondsSince > 15 * 60) {
            return new XpLane("FRESHNESS", XpHealth.CRIT, "No data for " + min + " minutes");
        }
        if (secondsSince > 5 * 60) {
            return new XpLane("FRESHNESS", XpHealth.WARN, "Last data " + min + " minutes ago");
        }
        return new XpLane("FRESHNESS", XpHealth.OK, "Reporting");
    }
}
