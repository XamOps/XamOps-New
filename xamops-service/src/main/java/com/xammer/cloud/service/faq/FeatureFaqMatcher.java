package com.xammer.cloud.service.faq;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

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

    private static final Pattern CAMEL_CASE_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    private int score(FeatureFaqEntry entry, String normalizedMessage) {
        int score = 0;
        if (containsWholeWordPhrase(normalizedMessage, entry.tabLabel())
                || containsWholeWordPhrase(normalizedMessage, spaceCamelCaseBoundaries(entry.tabLabel()))) {
            score += LABEL_MATCH_SCORE;
        }
        for (String keyword : entry.keywords()) {
            if (containsWholeWordPhrase(normalizedMessage, keyword)) {
                score += KEYWORD_MATCH_SCORE;
            }
        }
        return score;
    }

    /**
     * True if {@code phrase} appears in {@code normalizedMessage} on whole-word
     * boundaries — e.g. the keyword "ri" must not match inside "security".
     * Both sides are normalized and padded with sentinel spaces so one substring
     * check covers start/end boundaries uniformly, including multi-word phrases
     * like "reserved instances".
     */
    private static boolean containsWholeWordPhrase(String normalizedMessage, String phrase) {
        String paddedMessage = " " + normalizedMessage + " ";
        String paddedPhrase = " " + normalize(phrase) + " ";
        return paddedMessage.contains(paddedPhrase);
    }

    /**
     * Inserts a space at lower/digit-to-uppercase transitions, so a compound
     * brand label like "XamSecure" or "CloudK8s" also matches how a user
     * naturally types it with spacing ("xam secure", "cloud k8s") — without
     * this, only the exact one-word form would earn the label bonus.
     */
    private static String spaceCamelCaseBoundaries(String label) {
        return CAMEL_CASE_BOUNDARY.matcher(label).replaceAll(" ");
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
