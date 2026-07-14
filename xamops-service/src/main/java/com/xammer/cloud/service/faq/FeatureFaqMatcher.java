package com.xammer.cloud.service.faq;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic keyword/label matcher for the chatbot's feature/tab FAQ.
 * Scoring: +3 if the tab's own label appears as a phrase in the message,
 * +1 per catalog keyword found in the message. Minimum score to fire is 2.
 * A tie for the top score is treated as no match — escalating a message to
 * human support is safer than guessing between two ambiguous features.
 */
@Component
public class FeatureFaqMatcher {

    private static final int LABEL_MATCH_SCORE = 3;
    private static final int KEYWORD_MATCH_SCORE = 1;
    private static final int MIN_SCORE = 2;

    public Optional<FeatureFaqEntry> match(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return Optional.empty();
        }

        String normalizedMessage = normalize(rawMessage);

        List<ScoredEntry> scored = FeatureFaqCatalog.ENTRIES.stream()
                .map(entry -> new ScoredEntry(entry, score(entry, normalizedMessage)))
                .filter(se -> se.score() >= MIN_SCORE)
                .sorted(Comparator.comparingInt(ScoredEntry::score).reversed())
                .toList();

        if (scored.isEmpty()) {
            return Optional.empty();
        }
        if (scored.size() > 1 && scored.get(0).score() == scored.get(1).score()) {
            return Optional.empty();
        }
        return Optional.of(scored.get(0).entry());
    }

    private int score(FeatureFaqEntry entry, String normalizedMessage) {
        int score = 0;
        if (normalizedMessage.contains(normalize(entry.tabLabel()))) {
            score += LABEL_MATCH_SCORE;
        }
        for (String keyword : entry.keywords()) {
            if (normalizedMessage.contains(normalize(keyword))) {
                score += KEYWORD_MATCH_SCORE;
            }
        }
        return score;
    }

    private static String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record ScoredEntry(FeatureFaqEntry entry, int score) {
    }
}
