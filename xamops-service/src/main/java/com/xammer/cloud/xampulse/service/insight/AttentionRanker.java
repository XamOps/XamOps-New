package com.xammer.cloud.xampulse.service.insight;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Orders "needs attention" items: {@code impact = severity_weight x blast_radius x log10(1 + duration_min)},
 * {@code rank = impact x confidence}. Acknowledged or silenced items drop out entirely.
 */
@Service("xpAttentionRanker")
public class AttentionRanker {

    public record Candidate(String id, String severity, String confidence, int blastRadius, Instant since,
                            boolean acknowledged, boolean silenced) {}

    public record Ranked(Candidate candidate, double score) {}

    public List<Ranked> rank(List<Candidate> candidates, Instant now, int max) {
        return candidates.stream()
                .filter(c -> !c.acknowledged() && !c.silenced())
                .map(c -> new Ranked(c, score(c, now)))
                .sorted(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(r -> r.candidate().id()))
                .limit(max)
                .toList();
    }

    static double score(Candidate c, Instant now) {
        // Unknown or very recent start counts as one minute so a fresh critical item still outranks a quiet one.
        double minutes = Math.max(1, Duration.between(c.since(), now).toSeconds() / 60.0);
        double impact = severityWeight(c.severity()) * Math.max(1, c.blastRadius()) * Math.log10(1 + minutes);
        return impact * confidenceWeight(c.confidence());
    }

    static double severityWeight(String s) {
        return switch (s == null ? "" : s) {
            case "CRITICAL" -> 8;
            case "HIGH" -> 4;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    static double confidenceWeight(String c) {
        return switch (c == null ? "" : c) {
            case "HIGH" -> 1.0;
            case "MEDIUM" -> 0.7;
            default -> 0.4;
        };
    }
}
