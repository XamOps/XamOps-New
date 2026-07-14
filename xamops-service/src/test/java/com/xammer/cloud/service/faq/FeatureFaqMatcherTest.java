package com.xammer.cloud.service.faq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureFaqMatcherTest {

    private FeatureFaqMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new FeatureFaqMatcher();
    }

    @Test
    void match_returnsEntry_whenExactLabelPhrasePresent() {
        Optional<FeatureFaqEntry> result = matcher.match("what does disk rightsizing do?");

        assertThat(result).isPresent();
        assertThat(result.get().tabKey()).isEqualTo("finops-disk-rightsizing");
    }

    @Test
    void match_isCaseAndPunctuationInsensitive() {
        Optional<FeatureFaqEntry> result = matcher.match("WHAT DOES DISK RIGHTSIZING DO?!?!");

        assertThat(result).isPresent();
        assertThat(result.get().tabKey()).isEqualTo("finops-disk-rightsizing");
    }

    @Test
    void match_returnsEntry_whenEnoughKeywordsPresentWithoutExactLabel() {
        // "gp2" + "gp3" + "unattached" = 3 keyword hits, no label phrase — above threshold.
        Optional<FeatureFaqEntry> result = matcher.match("can you resize my gp2 volume to gp3, it's unattached");

        assertThat(result).isPresent();
        assertThat(result.get().tabKey()).isEqualTo("finops-disk-rightsizing");
    }

    @Test
    void match_returnsEmpty_whenBelowMinimumScore() {
        // "cost" alone is a single keyword hit (score 1) on multiple entries — below threshold of 2.
        Optional<FeatureFaqEntry> result = matcher.match("cost");

        assertThat(result).isEmpty();
    }

    @Test
    void match_returnsEmpty_whenTopScoreIsTiedBetweenTwoEntries() {
        // "Alerts" is a tab label shared by secops-alerts and obs-alerts — a bare
        // "what does alerts do" ties at the label-match score with no differentiating
        // keywords, so the matcher must not guess.
        Optional<FeatureFaqEntry> result = matcher.match("what does alerts do");

        assertThat(result).isEmpty();
    }

    @Test
    void match_breaksTie_whenOneEntryHasAdditionalKeywordHits() {
        // Still contains the tied "alerts" label match, but "cloudguard" is a
        // secops-alerts-only keyword, pushing its score above obs-alerts.
        Optional<FeatureFaqEntry> result = matcher.match("what does the cloudguard alerts tab do");

        assertThat(result).isPresent();
        assertThat(result.get().tabKey()).isEqualTo("secops-alerts");
    }

    @Test
    void match_returnsEmpty_forBlankOrNullMessage() {
        assertThat(matcher.match("")).isEmpty();
        assertThat(matcher.match("   ")).isEmpty();
        assertThat(matcher.match(null)).isEmpty();
    }

    @Test
    void match_returnsEmpty_forUnrelatedFreeTextMessage() {
        Optional<FeatureFaqEntry> result = matcher.match("hi, my invoice for last month looks wrong, can someone check?");

        assertThat(result).isEmpty();
    }

    @Test
    void match_doesNotFalsePositiveOnShortKeywordSubstring() {
        // Regression: "ri" is a finops-reservations keyword. Before the word-boundary
        // fix, it matched as a raw substring inside "security", combining with the
        // legitimate "coverage" keyword hit to reach MIN_SCORE and produce a wrong,
        // non-tied auto-answer about Reservations.
        Optional<FeatureFaqEntry> result = matcher.match("is there a security coverage issue on my account");

        assertThat(result).isEmpty();
    }
}
