package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.xampulse.dto.XpEvent;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks recent events as likely causes of a problem. Weights: proximity 0.4, magnitude 0.3, co-location 0.2,
 * history 0.1. Returns candidates, not conclusions; the caller labels them "possible cause".
 */
@Service("xpWhatChangedService")
public class WhatChangedService {

    public record Ranked(XpEvent event, double score) {}

    /**
     * @param problemAt     when the problem began
     * @param instanceId    affected resource (events whose resource equals it are co-located)
     * @param windowMinutes how far back to look
     * @param relatedBefore how many times this kind of event was previously followed by a problem (0 if unknown)
     */
    public List<Ranked> rank(Instant problemAt, String instanceId, List<XpEvent> events, int windowMinutes, int relatedBefore) {
        Duration window = Duration.ofMinutes(Math.max(1, windowMinutes));
        return events.stream()
                .filter(e -> !e.ts().isAfter(problemAt) && !e.ts().isBefore(problemAt.minus(window)))
                .map(e -> new Ranked(e, score(e, problemAt, instanceId, window, relatedBefore)))
                .sorted(Comparator.comparingDouble(Ranked::score).reversed())
                .limit(5)
                .toList();
    }

    private static double score(XpEvent e, Instant problemAt, String instanceId, Duration window, int relatedBefore) {
        double age = Duration.between(e.ts(), problemAt).toSeconds();
        double proximity = 1.0 - Math.min(1.0, age / Math.max(1, window.toSeconds()));
        double magnitude = switch (e.kind() == null ? "" : e.kind()) {
            case "DEPLOY", "CONFIG" -> 1.0;
            case "EC2_STATE" -> 0.9;
            case "ANOMALY", "ALERT" -> 0.6;
            default -> 0.3;
        };
        double colocation = instanceId != null && instanceId.equals(e.resource()) ? 1.0 : 0.0;
        double history = Math.min(1.0, relatedBefore / 3.0);
        return 0.4 * proximity + 0.3 * magnitude + 0.2 * colocation + 0.1 * history;
    }
}
