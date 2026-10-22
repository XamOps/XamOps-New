package com.xammer.cloud.service.automation.workload.rca;

import com.xammer.cloud.domain.WorkloadVerticalAction;
import com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The pure analyzer, asserted against literal row fixtures (design §2.9). No DB, no Spring, no
 * cluster — {@code now} is injected so every window cut is deterministic.
 */
class RcaAnalyzerTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final RcaConfig CONFIG = RcaConfig.defaults();

    private long nextId = 1;

    @Test
    @DisplayName("empty input produces no causes")
    void emptyInputIsEmpty() {
        assertThat(RcaAnalyzer.analyze(List.of(), CONFIG, NOW)).isEmpty();
        assertThat(RcaAnalyzer.analyze(null, CONFIG, NOW)).isEmpty();
    }

    @Test
    @DisplayName("REPEATED_FAILED at 3 occurrences scores 0.55, at 6 clamps to 1.00")
    void repeatedFailedConfidenceMatchesTheExactTriple() {
        List<WorkloadVerticalAction> three = rows("team-a", "throttled-encoder", "FAILED", 3);
        List<RootCause> causes = RcaAnalyzer.analyze(three, CONFIG, NOW);
        assertThat(causes).hasSize(1);
        RootCause cause = causes.get(0);
        assertThat(cause.kind()).isEqualTo(RcaPatternKind.REPEATED_FAILED);
        assertThat(cause.occurrences()).isEqualTo(3);
        assertThat(cause.confidence()).isEqualTo(0.55, within(1e-9));

        List<WorkloadVerticalAction> six = rows("team-a", "throttled-encoder", "FAILED", 6);
        assertThat(RcaAnalyzer.analyze(six, CONFIG, NOW).get(0).confidence())
                .isEqualTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("below the per-kind threshold produces no cause")
    void belowThresholdProducesNoCause() {
        // FAILED threshold is 2, so a single FAILED row does not fire.
        assertThat(RcaAnalyzer.analyze(rows("team-a", "api", "FAILED", 1), CONFIG, NOW)).isEmpty();
        // REFUSED threshold is 3, so two REFUSED rows do not fire.
        assertThat(RcaAnalyzer.analyze(rows("team-a", "api", "REFUSED", 2), CONFIG, NOW)).isEmpty();
    }

    @Test
    @DisplayName("REPEATED_REFUSED at 5 occurrences scores 0.70")
    void repeatedRefusedConfidence() {
        List<RootCause> causes = RcaAnalyzer.analyze(rows("ns", "x", "REFUSED", 5), CONFIG, NOW);
        assertThat(causes).singleElement().satisfies(cause -> {
            assertThat(cause.kind()).isEqualTo(RcaPatternKind.REPEATED_REFUSED);
            // 0.50 + 0.10 * (5 - 3) = 0.70
            assertThat(cause.confidence()).isEqualTo(0.70, within(1e-9));
        });
    }

    @Test
    @DisplayName("EXPIRY_CHURN fires on repeated EXPIRED rows")
    void expiryChurnFires() {
        List<RootCause> causes = RcaAnalyzer.analyze(rows("ns", "y", "EXPIRED", 4), CONFIG, NOW);
        assertThat(causes).singleElement().satisfies(cause -> {
            assertThat(cause.kind()).isEqualTo(RcaPatternKind.EXPIRY_CHURN);
            // 0.40 + 0.15 * (4 - 2) = 0.70
            assertThat(cause.confidence()).isEqualTo(0.70, within(1e-9));
            assertThat(cause.remediation()).contains("EXPIRED before landing").contains("ns/y");
        });
    }

    @Test
    @DisplayName("PARTIAL_APPLY_CHURN fires on partial applies and on non-restart-free applies")
    void partialApplyChurnFires() {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        rows.add(row("ns", "z", "PARTIALLY_APPLIED", NOW.minusSeconds(100), false, true, null));
        rows.add(row("ns", "z", "PARTIALLY_APPLIED", NOW.minusSeconds(200), false, true, null));
        // An APPLIED-but-not-restart-free row also counts.
        rows.add(row("ns", "z", "APPLIED", NOW.minusSeconds(300), false, false, null));

        List<RootCause> causes = RcaAnalyzer.analyze(rows, CONFIG, NOW);
        assertThat(causes).anySatisfy(cause -> {
            assertThat(cause.kind()).isEqualTo(RcaPatternKind.PARTIAL_APPLY_CHURN);
            assertThat(cause.occurrences()).isEqualTo(3);
            assertThat(cause.remediation()).contains("InPlacePodVerticalScaling");
        });
    }

    @Test
    @DisplayName("POLICY_CHANGE rows are filtered out before detection")
    void policyChangeRowsAreFiltered() {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("ns", "admin", WorkloadActionRecorder.OUTCOME_POLICY_CHANGE,
                    NOW.minusSeconds(100L * i), false, false, null));
        }
        assertThat(RcaAnalyzer.analyze(rows, CONFIG, NOW)).isEmpty();
    }

    @Test
    @DisplayName("out-of-window rows do not contribute")
    void outOfWindowRowsAreExcluded() {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        // Two in-window FAILED (fires alone? threshold 2) plus one far outside.
        rows.add(row("ns", "w", "FAILED", NOW.minusSeconds(3600), false, false, null));
        rows.add(row("ns", "w", "FAILED", NOW.minusSeconds(100), false, false, null));
        rows.add(row("ns", "w", "FAILED", NOW.minus(java.time.Duration.ofHours(48)), false, false, null));

        List<RootCause> causes = RcaAnalyzer.analyze(rows, CONFIG, NOW);
        assertThat(causes).singleElement().satisfies(cause ->
                assertThat(cause.occurrences()).isEqualTo(2));
    }

    @Test
    @DisplayName("mixed workloads are grouped and ranked by confidence descending")
    void mixedWorkloadsAreGroupedAndRanked() {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        rows.addAll(rows("ns", "low", "FAILED", 2));   // 0.40
        rows.addAll(rows("ns", "high", "REFUSED", 6));  // 0.50 + 0.10*3 = 0.80
        List<RootCause> causes = RcaAnalyzer.analyze(rows, CONFIG, NOW);
        assertThat(causes).hasSize(2);
        assertThat(causes.get(0).workload().name()).isEqualTo("high");
        assertThat(causes.get(0).confidence()).isGreaterThan(causes.get(1).confidence());
    }

    @Test
    @DisplayName("topK caps the number of causes")
    void topKCaps() {
        RcaConfig capped = new RcaConfig(2, 3, 2, 2, 3, 24, 1);
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        rows.addAll(rows("ns", "a", "FAILED", 3));
        rows.addAll(rows("ns", "b", "REFUSED", 6));
        assertThat(RcaAnalyzer.analyze(rows, capped, NOW)).hasSize(1);
    }

    // ------------------------------------------------------------------ RESIZE_FLIP_FLOP (§2.1a)

    @Test
    @DisplayName("RESIZE_FLIP_FLOP: 4 retained APPLIED rows with 3 reversals scores 0.70")
    void flipFlopFiresFromTheObservedCpuTrend() {
        // 100 -> 300 (+), 300 -> 150 (-), 150 -> 400 (+) : 3 sign reversals? deltas +,-,+
        // signs: +,-,+  -> reversals at positions 2 (+ to -) and 3 (- to +) = 2 reversals.
        // Need threshold 3 reversals -> use 5 rows: +,-,+,- = 3 reversals.
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(500), 100));
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(400), 300)); // +
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(300), 150)); // -
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(200), 400)); // +
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(100), 120)); // -  => 3 reversals, 5 retained

        List<RootCause> causes = RcaAnalyzer.analyze(rows, CONFIG, NOW);
        assertThat(causes).anySatisfy(cause -> {
            assertThat(cause.kind()).isEqualTo(RcaPatternKind.RESIZE_FLIP_FLOP);
            // occurrences = retained rows = 5: 0.60 + 0.10*(5-3) = 0.80
            assertThat(cause.occurrences()).isEqualTo(5);
            assertThat(cause.confidence()).isEqualTo(0.80, within(1e-9));
            assertThat(cause.remediation()).contains("oscillating");
        });
    }

    @Test
    @DisplayName("RESIZE_FLIP_FLOP: threshold-1 reversals produces no cause")
    void flipFlopBelowThresholdDoesNotFire() {
        // +,-,+ => 2 reversals, below threshold 3.
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(400), 100));
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(300), 300)); // +
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(200), 150)); // -
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(100), 400)); // +  => 2 reversals

        assertThat(RcaAnalyzer.analyze(rows, CONFIG, NOW))
                .noneMatch(c -> c.kind() == RcaPatternKind.RESIZE_FLIP_FLOP);
    }

    @Test
    @DisplayName("a null-afterJson and a missing-cpuRequestMillis row are skipped, not reversals")
    void flipFlopSkipsGapRowsAndMatchesTheGaplessSequence() {
        // Gapless sequence that fires: 100,300,150,400,120 -> 3 reversals.
        List<WorkloadVerticalAction> gapless = new ArrayList<>();
        gapless.add(appliedCpu("ns", "api", NOW.minusSeconds(600), 100));
        gapless.add(appliedCpu("ns", "api", NOW.minusSeconds(500), 300));
        gapless.add(appliedCpu("ns", "api", NOW.minusSeconds(400), 150));
        gapless.add(appliedCpu("ns", "api", NOW.minusSeconds(300), 400));
        gapless.add(appliedCpu("ns", "api", NOW.minusSeconds(200), 120));
        RootCause gaplessCause = flipFlop(RcaAnalyzer.analyze(gapless, CONFIG, NOW));

        // Same sequence but with a null-afterJson row and a missing-cpuRequestMillis row interleaved.
        List<WorkloadVerticalAction> withGaps = new ArrayList<>();
        withGaps.add(appliedCpu("ns", "api", NOW.minusSeconds(600), 100));
        withGaps.add(appliedCpu("ns", "api", NOW.minusSeconds(550), 300));
        withGaps.add(row("ns", "api", "APPLIED", NOW.minusSeconds(520), false, true, null)); // null afterJson
        withGaps.add(appliedCpu("ns", "api", NOW.minusSeconds(500), 150));
        // afterJson present but no cpuRequestMillis (limits-only apply):
        withGaps.add(row("ns", "api", "APPLIED", NOW.minusSeconds(450), false, true,
                "[{\"container\":\"api\",\"memRequestBytes\":1048576}]"));
        withGaps.add(appliedCpu("ns", "api", NOW.minusSeconds(400), 400));
        withGaps.add(appliedCpu("ns", "api", NOW.minusSeconds(300), 120));
        RootCause withGapsCause = flipFlop(RcaAnalyzer.analyze(withGaps, CONFIG, NOW));

        assertThat(withGapsCause.occurrences()).isEqualTo(gaplessCause.occurrences());
        assertThat(withGapsCause.confidence()).isEqualTo(gaplessCause.confidence(), within(1e-9));
    }

    @Test
    @DisplayName("zero deltas carry no direction and are dropped before the reversal scan")
    void flipFlopDropsZeroDeltas() {
        // 100,100,300,100,300 -> non-zero signs +,-,+ (the repeat 100->100 is a zero delta) = 2 reversals.
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(500), 100));
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(400), 100)); // zero delta
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(300), 300)); // +
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(200), 100)); // -
        rows.add(appliedCpu("ns", "api", NOW.minusSeconds(100), 300)); // +  => 2 reversals, below 3

        assertThat(RcaAnalyzer.analyze(rows, CONFIG, NOW))
                .noneMatch(c -> c.kind() == RcaPatternKind.RESIZE_FLIP_FLOP);
    }

    // ------------------------------------------------------------------ remediation enrichment

    @Test
    @DisplayName("REPEATED_FAILED remediation is enriched with insufficient cpu when the detail says so")
    void failedRemediationEnrichesFromRecognisedToken() {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        rows.add(row("prod-sim", "throttled-encoder", "FAILED", NOW.minusSeconds(100), false, false,
                null, "node reported insufficient cpu for this pod"));
        rows.add(row("prod-sim", "throttled-encoder", "FAILED", NOW.minusSeconds(200), false, false,
                null, "another failure"));
        RootCause cause = RcaAnalyzer.analyze(rows, CONFIG, NOW).get(0);
        assertThat(cause.remediation()).contains("node reported insufficient cpu")
                .contains("node autoscaling");
    }

    @Test
    @DisplayName("opaque detail leaves the generic remediation standing")
    void opaqueDetailDoesNotEnrich() {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        rows.add(row("ns", "api", "FAILED", NOW.minusSeconds(100), false, false, null,
                "some free text the analyzer must not parse"));
        rows.add(row("ns", "api", "FAILED", NOW.minusSeconds(200), false, false, null,
                "more opaque text"));
        RootCause cause = RcaAnalyzer.analyze(rows, CONFIG, NOW).get(0);
        assertThat(cause.remediation()).contains("Check the executor's detail")
                .doesNotContain("insufficient");
    }

    @Test
    @DisplayName("REPEATED_REFUSED remediation is enriched when GITOPS_MANAGED is present")
    void refusedRemediationEnrichesWithGitops() {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(row("ns", "argo-app", "REFUSED", NOW.minusSeconds(100L * (i + 1)), false, false,
                    null, "refused by GITOPS_MANAGED safety rule"));
        }
        RootCause cause = RcaAnalyzer.analyze(rows, CONFIG, NOW).get(0);
        assertThat(cause.remediation()).contains("GITOPS_MANAGED").contains("Argo CD/Flux");
    }

    // ------------------------------------------------------------------ the activity log's rows

    @Test
    @DisplayName("an HPA's own rows are never resize evidence: applied HPA changes are not partial-apply churn")
    void hpaRowsAreNotResizeEvidence() {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            // As the recorder writes every HPA apply: the HPA's kind and name, restart_free false.
            long ago = 100L * (i + 1);
            rows.add(hpa("team-b", "php-apache", "APPLIED", NOW.minusSeconds(ago)));
            rows.add(hpa("team-b", "php-apache", "FAILED", NOW.minusSeconds(ago + 10)));
            rows.add(hpa("team-b", "php-apache", "EXPIRED", NOW.minusSeconds(ago + 20)));
            rows.add(hpa("team-b", "php-apache", "REFUSED", NOW.minusSeconds(ago + 30)));
        }

        assertThat(RcaAnalyzer.analyze(rows, CONFIG, NOW)).isEmpty();
        // The Deployment of the same name keeps its own patterns.
        rows.add(row("team-b", "php-apache", "APPLIED", NOW.minusSeconds(50), false, false, null));
        rows.add(row("team-b", "php-apache", "PARTIALLY_APPLIED", NOW.minusSeconds(60), false, false, null));
        assertThat(RcaAnalyzer.analyze(rows, CONFIG, NOW)).singleElement().satisfies(cause -> {
            assertThat(cause.kind()).isEqualTo(RcaPatternKind.PARTIAL_APPLY_CHURN);
            assertThat(cause.workload().kind()).isEqualTo("Deployment");
            assertThat(cause.occurrences()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("a resize row stored with its proof object reads as flip-flop evidence, as an older array row does")
    void proofObjectRowsAreFlipFlopEvidence() {
        long[] cpu = {100, 300, 150, 400, 120};
        List<WorkloadVerticalAction> proofRows = new ArrayList<>();
        List<WorkloadVerticalAction> arrayRows = new ArrayList<>();
        for (int i = 0; i < cpu.length; i++) {
            Instant at = NOW.minusSeconds(500 - 100L * i);
            proofRows.add(row("ns", "api", "APPLIED", at, false, true, proofObject("api", 250, cpu[i])));
            arrayRows.add(appliedCpu("ns", "api", at, cpu[i]));
        }

        RootCause fromProof = flipFlop(RcaAnalyzer.analyze(proofRows, CONFIG, NOW));
        RootCause fromArrays = flipFlop(RcaAnalyzer.analyze(arrayRows, CONFIG, NOW));
        assertThat(fromProof.occurrences()).isEqualTo(5).isEqualTo(fromArrays.occurrences());
        assertThat(fromProof.confidence()).isEqualTo(fromArrays.confidence(), within(1e-9));
        assertThat(RcaAnalyzer.cpuRequestMillis(proofObject("api", 250, 63))).isEqualTo(63L);
    }

    @Test
    @DisplayName("test runs and applies that changed no pod match no pattern; a user's repeated refusals are REPEATED_REFUSED")
    void attemptRowsMatchOnlyTheirOwnPattern() {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long ago = 100L * (i + 1);
            rows.add(row("ns", "api", "TEST_RUN", NOW.minusSeconds(ago), true, false,
                    "{\"terminal\":\"DRY_RUN\",\"reverted\":null,\"containers\":[],\"pods\":[]}"));
            // A deferral XamOps put back: the observed size after is the old one.
            rows.add(row("ns", "api", "NOT_CHANGED", NOW.minusSeconds(ago + 10), false, false,
                    proofObject("api", 250, 250).replace("\"terminal\":\"APPLIED\"", "\"terminal\":\"DEFERRED\"")));
        }
        assertThat(RcaAnalyzer.analyze(rows, CONFIG, NOW)).isEmpty();

        // As the recorder writes a user's apply a gate refused: no after_json, detail "<code>: <message>".
        for (int i = 0; i < 3; i++) {
            rows.add(row("ns", "api", "REFUSED", NOW.minusSeconds(1000L + i), false, false, null,
                    "K8S_INVALID_POLICY: COOLDOWN: a change was applied recently."));
        }
        assertThat(RcaAnalyzer.analyze(rows, CONFIG, NOW)).singleElement()
                .satisfies(cause -> assertThat(cause.kind()).isEqualTo(RcaPatternKind.REPEATED_REFUSED));
    }

    @Test
    @DisplayName("every emitted cause carries its evidence rows")
    void evidenceTravelsWithEveryCause() {
        List<WorkloadVerticalAction> rows = rows("ns", "api", "FAILED", 3);
        RootCause cause = RcaAnalyzer.analyze(rows, CONFIG, NOW).get(0);
        assertThat(cause.evidence()).hasSize(3);
        assertThat(cause.evidence()).allSatisfy(e -> assertThat(e.outcome()).isEqualTo("FAILED"));
    }

    // ------------------------------------------------------------------ fixtures

    private RootCause flipFlop(List<RootCause> causes) {
        return causes.stream().filter(c -> c.kind() == RcaPatternKind.RESIZE_FLIP_FLOP)
                .findFirst().orElseThrow();
    }

    private List<WorkloadVerticalAction> rows(String ns, String name, String outcome, int count) {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(row(ns, name, outcome, NOW.minusSeconds(100L * (i + 1)), false, false, null));
        }
        return rows;
    }

    private WorkloadVerticalAction appliedCpu(String ns, String name, Instant at, long cpuMillis) {
        return row(ns, name, "APPLIED", at, false, true,
                "[{\"container\":\"" + name + "\",\"cpuRequestMillis\":" + cpuMillis + "}]");
    }

    /**
     * A resize's after_json as the activity log's recorder writes it: terminal first, each container
     * with the older rows' flat observed-after sizes and both observed sides, then the per-pod proof.
     */
    private static String proofObject(String container, long beforeMillis, long afterMillis) {
        return "{\"terminal\":\"APPLIED\",\"reverted\":null,\"containers\":[{\"container\":\"" + container
                + "\",\"cpuRequestMillis\":" + afterMillis + ",\"observedBefore\":{\"cpuRequestMillis\":"
                + beforeMillis + ",\"cpuLimitMillis\":null,\"memRequestBytes\":null,\"memLimitBytes\":null},"
                + "\"observedAfter\":{\"cpuRequestMillis\":" + afterMillis + ",\"cpuLimitMillis\":null,"
                + "\"memRequestBytes\":null,\"memLimitBytes\":null}}],\"pods\":[{\"pod\":\"" + container
                + "-7c9d\",\"node\":null,\"changed\":true,\"uidChanged\":false,\"restartsBefore\":0,"
                + "\"restartsAfter\":0,\"ready\":true,\"revert\":null}]}";
    }

    /** An HPA's own row, as the recorder writes every HPA apply. */
    private WorkloadVerticalAction hpa(String ns, String name, String outcome, Instant at) {
        WorkloadVerticalAction row = row(ns, name, outcome, at, false, false,
                "{\"minReplicas\":1,\"maxReplicas\":8,\"targetUtilizationPct\":50}");
        row.setWorkloadKind(RcaAnalyzer.KIND_HPA);
        row.setBeforeJson("{\"minReplicas\":1,\"maxReplicas\":5,\"targetUtilizationPct\":50}");
        return row;
    }

    private WorkloadVerticalAction row(String ns, String name, String outcome, Instant at,
                                       boolean dryRun, boolean restartFree, String afterJson) {
        return row(ns, name, outcome, at, dryRun, restartFree, afterJson, "detail");
    }

    private WorkloadVerticalAction row(String ns, String name, String outcome, Instant at,
                                       boolean dryRun, boolean restartFree, String afterJson,
                                       String detail) {
        WorkloadVerticalAction row = new WorkloadVerticalAction();
        row.setId(nextId++);
        row.setClientId(7L);
        row.setClusterConfigId(42L);
        row.setNamespace(ns);
        row.setWorkloadKind("Deployment");
        row.setWorkloadName(name);
        row.setTriggerSource("MANUAL");
        row.setDryRun(dryRun);
        row.setOutcome(outcome);
        row.setRestartFree(restartFree);
        row.setAfterJson(afterJson);
        row.setDetail(detail);
        row.setCorrelationId("corr-" + row.getId());
        row.setAppliedAt(at);
        return row;
    }
}
