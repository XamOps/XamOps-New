package com.xammer.cloud.service.automation.workload.usage;

import com.xammer.cloud.service.automation.workload.PodNameRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the historical pod selector actually matches, decided by a real regex engine.
 *
 * <p>Orchestrator addendum A asked for the pods a workload owned <em>during the window</em>, not just
 * the ones alive now. The risk in widening a selector is re-creating the audit's prefix match, so
 * these tests take the selector {@code PromQl} builds, anchor it exactly the way Prometheus does
 * ({@code ^(?:…)$} — see the Prometheus docs on {@code =~}), and assert pod name by pod name which
 * ones are in and which are out.
 */
class PodNameRuleSelectorTest {

    @Test
    @DisplayName("a retained ReplicaSet's pods match; the canary Deployment's never do")
    void aReplicaSetRuleCannotClaimAnotherWorkloadsPods() {
        Pattern selector = anchored(new PromQl.Selector("team-a",
                List.of("php-apache-4e7a-aaaaa"),
                List.of(PodNameRule.replicaSetPods("php-apache-4e7a"),
                        PodNameRule.replicaSetPods("php-apache-1b3d")),
                List.of("php-apache")));

        // In: the live pod by name, any pod of either retained revision -- including the pods of the
        // old revision, which is the entire point of the rule.
        assertThat(selector.matcher("php-apache-4e7a-aaaaa").matches()).isTrue();
        assertThat(selector.matcher("php-apache-4e7a-zq8m2").matches()).isTrue();
        assertThat(selector.matcher("php-apache-1b3d-hk4p9").matches()).isTrue();

        // Out: the sibling Deployment the Go rightsizer's pod=~"php-apache-.*" swallowed. Its pods
        // are php-apache-canary-<hash>-<5>, which is longer than any rule can match.
        assertThat(selector.matcher("php-apache-canary-6f2a-aaaaa").matches()).isFalse();
        assertThat(selector.matcher("php-apache-canary-6f2a").matches()).isFalse();
        // Out: a third revision that is no longer retained -- under-counted, never invented.
        assertThat(selector.matcher("php-apache-9c1e-aaaaa").matches()).isFalse();
        // Out: the Deployment's own name with no ReplicaSet hash, and a suffix of the wrong length.
        assertThat(selector.matcher("php-apache-aaaaa").matches()).isFalse();
        assertThat(selector.matcher("php-apache-4e7a-aaaa").matches()).isFalse();
        assertThat(selector.matcher("php-apache-4e7a-aaaaaa").matches()).isFalse();
    }

    @Test
    @DisplayName("a StatefulSet rule matches every ordinal, present or past, and nothing else")
    void aStatefulSetRuleMatchesOrdinalsOnly() {
        Pattern selector = anchored(new PromQl.Selector("prod-sim", List.of("stateful-queue-0"),
                List.of(PodNameRule.statefulSetPods("stateful-queue")), List.of("queue")));

        assertThat(selector.matcher("stateful-queue-0").matches()).isTrue();
        assertThat(selector.matcher("stateful-queue-7").matches()).isTrue();
        assertThat(selector.matcher("stateful-queue-12").matches()).isTrue();

        assertThat(selector.matcher("stateful-queue-replica-0").matches()).isFalse();
        assertThat(selector.matcher("stateful-queue-0-0").matches()).isFalse();
        assertThat(selector.matcher("stateful-queue").matches()).isFalse();
    }

    @Test
    @DisplayName("the literal part of a rule is regex-escaped, so a name cannot smuggle a pattern")
    void theLiteralPartIsEscaped() {
        PromQl.Selector selector = new PromQl.Selector("team-a", List.of("api-1"),
                List.of(PodNameRule.replicaSetPods("api.v2")), List.of("api"));

        // On the wire the dot is preceded by an escaped backslash, as a PromQL (Go) string needs.
        assertThat(selector.labels()).contains("pod=~\"api-1|api\\\\.v2-[a-z0-9]{5}\"");
        assertThat(anchored(selector).matcher("apiXv2-aaaaa").matches())
                .as("an unescaped dot would make any character match")
                .isFalse();
        assertThat(anchored(selector).matcher("api.v2-aaaaa").matches()).isTrue();
    }

    @Test
    @DisplayName("no rule can express an unbounded match")
    void noRuleCanExpressAWildcard() {
        for (PodNameRule.Kind kind : PodNameRule.Kind.values()) {
            String rendered = PromQl.ruleMatcher(new PodNameRule("api", kind));
            assertThat(rendered).doesNotContain(".*").doesNotContain(".+").doesNotContain("(");
        }
        // The only caller-supplied part is the prefix, and it must be a real name.
        assertThatThrownBy(() -> new PodNameRule(" ", PodNameRule.Kind.REPLICA_SET_POD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every pod in the namespace");
        assertThatThrownBy(() -> PodNameRule.replicaSetPods(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rules are optional: with none, the selector is exactly the current pod list")
    void rulesAreOptional() {
        PromQl.Selector withoutRules =
                new PromQl.Selector("team-a", List.of("api-1", "api-2"), List.of("api"));

        assertThat(withoutRules.podNameRules()).isEmpty();
        assertThat(withoutRules.labels())
                .isEqualTo("namespace=\"team-a\",pod=~\"api-1|api-2\",container=~\"api\"");
    }

    @Test
    @DisplayName("a rule that duplicates another is not repeated in the selector")
    void duplicateRulesCollapse() {
        PromQl.Selector selector = new PromQl.Selector("team-a", List.of("api-1"),
                List.of(PodNameRule.replicaSetPods("api-7d9f"),
                        PodNameRule.replicaSetPods("api-7d9f")),
                List.of("api"));

        assertThat(selector.labels()).contains("pod=~\"api-1|api-7d9f-[a-z0-9]{5}\"");
    }

    /** The selector as Prometheus itself evaluates it: {@code =~} is anchored at both ends. */
    private static Pattern anchored(PromQl.Selector selector) {
        String labels = selector.labels();
        String podMatcher = labels.substring(labels.indexOf("pod=~\"") + 6,
                labels.indexOf("\",container=~\""));
        return Pattern.compile("^(?:" + promqlString(podMatcher) + ")$");
    }

    /**
     * The value of a PromQL double-quoted string, decoded the way the PromQL parser decodes it (Go
     * escaping). An escape Go does not know, such as a backslash before a dot, fails the whole
     * query in Prometheus, so it fails here too (review S4).
     */
    private static String promqlString(String quotedBody) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < quotedBody.length(); i++) {
            char c = quotedBody.charAt(i);
            if (c != '\\') {
                value.append(c);
                continue;
            }
            char next = quotedBody.charAt(++i);
            switch (next) {
                case '\\' -> value.append('\\');
                case '"' -> value.append('"');
                case 'n' -> value.append('\n');
                case 't' -> value.append('\t');
                default -> throw new AssertionError("PromQL rejects the escape sequence \\" + next
                        + " in \"" + quotedBody + "\"");
            }
        }
        return value.toString();
    }

    @Test
    @DisplayName("every generated matcher is a valid PromQL string, so a dotted name cannot blank the cluster")
    void aDottedNameIsAValidPromQlString() {
        PromQl.Selector selector = new PromQl.Selector("team-a", List.of("api.v2-7d9f-aaaaa"),
                List.of(PodNameRule.replicaSetPods("api.v2-7d9f")), List.of("api.main"));
        String labels = selector.labels();
        String containers = labels.substring(labels.indexOf("container=~\"") + 12, labels.length() - 1);

        assertThat(anchored(selector).matcher("api.v2-7d9f-aaaaa").matches()).isTrue();
        assertThat(anchored(selector).matcher("apiXv2-7d9f-aaaaa").matches()).isFalse();
        assertThat(Pattern.compile("^(?:" + promqlString(containers) + ")$").matcher("api.main").matches())
                .isTrue();
    }
}
