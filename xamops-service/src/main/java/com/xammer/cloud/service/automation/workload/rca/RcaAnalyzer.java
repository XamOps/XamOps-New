package com.xammer.cloud.service.automation.workload.rca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.WorkloadVerticalAction;
import com.xammer.cloud.service.automation.workload.ContainerReason;
import com.xammer.cloud.service.automation.workload.HpaReason;
import com.xammer.cloud.service.automation.workload.api.ReasonCode;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The pure root-cause analyzer over XamOps's durable {@code vertical_workload_action} audit history
 * (design §2.5).
 *
 * <p>A <strong>pure function</strong>: {@link #analyze(List, RcaConfig, Instant)} takes rows, a
 * config and an injected {@code now}, and returns a deterministic ranked list. No I/O, no Spring, no
 * cluster or AWS call, no persistence — exactly like {@code WorkloadAutoscalingEngine}. It is
 * unit-tested against literal row fixtures.
 *
 * <h2>What it does, in order</h2>
 *
 * <ol>
 *   <li>Drop {@code POLICY_CHANGE} rows (an admin verb, never resize evidence) and rows outside the
 *       trailing window.</li>
 *   <li>Group the rest by workload key ({@code clusterConfigId + namespace + kind + name}).</li>
 *   <li>Per group, detect the five emitted {@link RcaPatternKind}s from durable columns only.</li>
 *   <li>Score each with {@code clamp(base + perOccurrence*(occurrences - threshold), 0, 1)}.</li>
 *   <li>Build a concrete remediation sentence, enriched only with a recognised emitted token.</li>
 *   <li>Sort by confidence desc (tiebreak: most recent {@code appliedAt}, then workload key) and cap
 *       to {@code topK}.</li>
 * </ol>
 *
 * <p>{@link RcaPatternKind#OOM_EVICTION} and {@link RcaPatternKind#HPA_FLAP} are never emitted: they
 * depend on data XamOps does not yet persist and are surfaced as honest dependencies elsewhere.
 */
public final class RcaAnalyzer {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The tokens this analyzer will recognise in the free-text {@code detail} column and use to
     * enrich a remediation sentence. Verified against the real proof → recorder path: these are
     * {@link ReasonCode}, {@link HpaReason} and {@link ContainerReason} names actually emitted, plus
     * the two {@code ResizeProof.insufficientResource()} values {@code cpu}/{@code memory}. Any other
     * text is treated as opaque — never parsed, never counted toward confidence.
     */
    private static final Set<String> RECOGNISED_TOKENS = recognisedTokens();

    /**
     * {@code workload_kind} of an HPA's own row ({@code WorkloadActionRecorder.KIND_HPA} on the
     * activity-log branch): never resize evidence.
     */
    static final String KIND_HPA = "HorizontalPodAutoscaler";

    private RcaAnalyzer() {
    }

    /**
     * Analyse one cluster's durable action rows into a ranked list of root causes.
     *
     * @param rows   every {@code vertical_workload_action} for the cluster (any order)
     * @param config the thresholds, window and top-K
     * @param now    the instant "now" for the trailing-window cut; injected for determinism
     * @return the ranked causes, deterministic and capped to {@code topK}
     */
    public static List<RootCause> analyze(List<WorkloadVerticalAction> rows, RcaConfig config,
                                          Instant now) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Instant windowStart = now.minusSeconds((long) config.windowHours() * 3600L);

        // Filter POLICY_CHANGE (admin verb), HPA rows and out-of-window rows, then group by workload key.
        Map<String, List<WorkloadVerticalAction>> byWorkload = new LinkedHashMap<>();
        for (WorkloadVerticalAction row : rows) {
            if (WorkloadActionRecorder.OUTCOME_POLICY_CHANGE.equals(row.getOutcome())) {
                continue;
            }
            // The activity log records every HPA apply in this table too, under the HPA's own kind
            // and name. An HPA patch changes bounds, never a pod's size, and is always written
            // restart_free=false, so the resize patterns would read applied HPA changes as
            // partial-apply churn. HPA_FLAP, the HPA's own pattern, is not emitted yet.
            if (KIND_HPA.equals(row.getWorkloadKind())) {
                continue;
            }
            Instant at = row.getAppliedAt();
            if (at == null || at.isBefore(windowStart)) {
                continue;
            }
            byWorkload.computeIfAbsent(workloadKey(row), ignored -> new ArrayList<>()).add(row);
        }

        List<RootCause> causes = new ArrayList<>();
        for (List<WorkloadVerticalAction> group : byWorkload.values()) {
            causes.addAll(detect(group, config));
        }

        causes.sort(RANKING);
        if (causes.size() > config.topK()) {
            return List.copyOf(causes.subList(0, config.topK()));
        }
        return List.copyOf(causes);
    }

    /** Most-confident first; deterministic tiebreak by most recent evidence then workload key. */
    private static final Comparator<RootCause> RANKING = Comparator
            .comparingDouble(RootCause::confidence).reversed()
            .thenComparing(Comparator.comparing(RcaAnalyzer::latestEvidenceEpoch).reversed())
            .thenComparing(c -> workloadKey(c.workload()));

    private static long latestEvidenceEpoch(RootCause cause) {
        long latest = Long.MIN_VALUE;
        for (Evidence e : cause.evidence()) {
            if (e.at() != null && e.at().toEpochMilli() > latest) {
                latest = e.at().toEpochMilli();
            }
        }
        return latest;
    }

    // ------------------------------------------------------------------ detection

    private static List<RootCause> detect(List<WorkloadVerticalAction> group, RcaConfig config) {
        List<RootCause> causes = new ArrayList<>();
        VerticalWorkloadDto.WorkloadRefDto ref = refOf(group.get(0));

        countOutcome(causes, group, config, ref, RcaPatternKind.REPEATED_FAILED, OUTCOME_FAILED);
        countOutcome(causes, group, config, ref, RcaPatternKind.REPEATED_REFUSED, OUTCOME_REFUSED);
        countOutcome(causes, group, config, ref, RcaPatternKind.EXPIRY_CHURN, OUTCOME_EXPIRED);
        detectPartialApplyChurn(causes, group, config, ref);
        detectFlipFlop(causes, group, config, ref);

        return causes;
    }

    private static final String OUTCOME_APPLIED = "APPLIED";
    private static final String OUTCOME_PARTIAL = "PARTIALLY_APPLIED";
    private static final String OUTCOME_REFUSED = "REFUSED";
    private static final String OUTCOME_FAILED = "FAILED";
    private static final String OUTCOME_EXPIRED = "EXPIRED";

    /** A simple "N rows with this outcome" pattern (FAILED / REFUSED / EXPIRED). */
    private static void countOutcome(List<RootCause> out, List<WorkloadVerticalAction> group,
                                     RcaConfig config, VerticalWorkloadDto.WorkloadRefDto ref,
                                     RcaPatternKind kind, String outcome) {
        List<WorkloadVerticalAction> matched = group.stream()
                .filter(r -> outcome.equals(r.getOutcome()))
                .toList();
        int threshold = config.thresholdFor(kind);
        if (matched.size() < threshold) {
            return;
        }
        out.add(cause(kind, ref, matched.size(), config, evidence(matched), remediation(kind, ref,
                matched.size(), config.windowHours(), enrichmentToken(matched))));
    }

    /**
     * Partial-apply churn: a non-dry-run apply that was {@code PARTIALLY_APPLIED}, or an
     * {@code APPLIED} row that was not restart-free (design §2.1 table).
     */
    private static void detectPartialApplyChurn(List<RootCause> out,
                                                List<WorkloadVerticalAction> group, RcaConfig config,
                                                VerticalWorkloadDto.WorkloadRefDto ref) {
        List<WorkloadVerticalAction> matched = group.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getDryRun()))
                .filter(r -> OUTCOME_PARTIAL.equals(r.getOutcome())
                        || (OUTCOME_APPLIED.equals(r.getOutcome()) && !Boolean.TRUE.equals(r.getRestartFree())))
                .toList();
        int threshold = config.thresholdFor(RcaPatternKind.PARTIAL_APPLY_CHURN);
        if (matched.size() < threshold) {
            return;
        }
        out.add(cause(RcaPatternKind.PARTIAL_APPLY_CHURN, ref, matched.size(), config,
                evidence(matched),
                remediation(RcaPatternKind.PARTIAL_APPLY_CHURN, ref, matched.size(),
                        config.windowHours(), enrichmentToken(matched))));
    }

    /**
     * Resize flip-flop (design §2.1a): the sign of the observed {@code afterJson.cpuRequestMillis}
     * delta across <strong>consecutive APPLIED non-dry-run</strong> rows, ascending {@code appliedAt}
     * (id tiebreak). Rows with a null {@code afterJson} or a missing {@code cpuRequestMillis} are
     * skipped — not counted as a reversal, and the comparison continues to the next retained row.
     * Zero deltas carry no direction and are dropped. The pattern fires at {@code >= threshold} sign
     * reversals; {@code occurrences} is the number of retained participating rows.
     */
    private static void detectFlipFlop(List<RootCause> out, List<WorkloadVerticalAction> group,
                                       RcaConfig config, VerticalWorkloadDto.WorkloadRefDto ref) {
        List<WorkloadVerticalAction> applied = group.stream()
                .filter(r -> OUTCOME_APPLIED.equals(r.getOutcome()))
                .filter(r -> !Boolean.TRUE.equals(r.getDryRun()))
                .sorted(APPLIED_ORDER)
                .toList();

        // Retain only rows whose afterJson carries a numeric cpuRequestMillis.
        List<WorkloadVerticalAction> retained = new ArrayList<>();
        List<Long> cpu = new ArrayList<>();
        for (WorkloadVerticalAction row : applied) {
            Long value = cpuRequestMillis(row.getAfterJson());
            if (value == null) {
                continue; // null afterJson or missing cpuRequestMillis: skip, not a reversal
            }
            retained.add(row);
            cpu.add(value);
        }
        if (retained.size() < 2) {
            return;
        }

        int reversals = 0;
        int lastSign = 0;
        for (int i = 1; i < cpu.size(); i++) {
            long delta = cpu.get(i) - cpu.get(i - 1);
            int sign = Long.signum(delta);
            if (sign == 0) {
                continue; // zero delta: no direction, dropped before the reversal scan
            }
            if (lastSign != 0 && sign != lastSign) {
                reversals++;
            }
            lastSign = sign;
        }

        int threshold = config.thresholdFor(RcaPatternKind.RESIZE_FLIP_FLOP);
        if (reversals < threshold) {
            return;
        }
        int occurrences = retained.size();
        out.add(cause(RcaPatternKind.RESIZE_FLIP_FLOP, ref, occurrences, config, evidence(retained),
                remediation(RcaPatternKind.RESIZE_FLIP_FLOP, ref, reversals, config.windowHours(),
                        null)));
    }

    /** Ascending appliedAt, id tiebreak; nulls last so a null stamp never leads the sequence. */
    private static final Comparator<WorkloadVerticalAction> APPLIED_ORDER = Comparator
            .comparing(WorkloadVerticalAction::getAppliedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(WorkloadVerticalAction::getId,
                    Comparator.nullsLast(Comparator.naturalOrder()));

    // ------------------------------------------------------------------ confidence + remediation

    private static RootCause cause(RcaPatternKind kind, VerticalWorkloadDto.WorkloadRefDto ref,
                                   int occurrences, RcaConfig config, List<Evidence> evidence,
                                   String remediation) {
        double confidence = confidence(kind, occurrences, config.thresholdFor(kind));
        return new RootCause(kind, title(kind), confidence, ref, remediation, occurrences,
                config.windowHours(), evidence);
    }

    /** {@code clamp(base + perOccurrence * (occurrences - threshold), 0, 1)} (design §2.3). */
    static double confidence(RcaPatternKind kind, int occurrences, int threshold) {
        double raw = kind.base() + kind.perOccurrence() * (occurrences - threshold);
        if (raw < 0) {
            return 0.0;
        }
        return Math.min(1.0, raw);
    }

    private static String title(RcaPatternKind kind) {
        return switch (kind) {
            case REPEATED_FAILED -> "Repeated apply failure";
            case REPEATED_REFUSED -> "Repeatedly refused";
            case PARTIAL_APPLY_CHURN -> "Partial-apply churn";
            case EXPIRY_CHURN -> "Applies expiring before they land";
            case RESIZE_FLIP_FLOP -> "Recommendation is oscillating";
            case OOM_EVICTION -> "OOM / eviction";
            case HPA_FLAP -> "HPA replica flapping";
        };
    }

    /**
     * The concrete, data-filled remediation sentence (design §2.4), enriched only when a recognised
     * token is present (else the generic copy stands). Never claims a cause the evidence does not
     * support.
     */
    private static String remediation(RcaPatternKind kind, VerticalWorkloadDto.WorkloadRefDto ref,
                                       int count, int windowHours, String token) {
        String workload = ref.namespace() + "/" + ref.name();
        return switch (kind) {
            case REPEATED_FAILED -> {
                String base = count + " FAILED applies in " + windowHours + "h on " + workload + ".";
                if ("cpu".equals(token) || "memory".equals(token)) {
                    yield base + " (node reported insufficient " + token
                            + ") — consider node autoscaling or a smaller " + token + " target.";
                }
                yield base + " Check the executor's detail for why each apply failed.";
            }
            case REPEATED_REFUSED -> {
                String base = count + " applies on " + workload + " were refused.";
                if (HpaReason.GITOPS_MANAGED.name().equals(token)
                        || ReasonCode.GITOPS_MANAGED.name().equals(token)) {
                    yield base + " by the GITOPS_MANAGED safety rule — the workload is owned by"
                            + " Argo CD/Flux. Change it in Git, not in XamOps.";
                }
                yield base + " Review the safety rule that keeps declining the apply.";
            }
            case EXPIRY_CHURN -> count + " applies on " + workload + " EXPIRED before landing — the"
                    + " agent may not be draining the command queue. Check the XamOps agent's last"
                    + " check-in.";
            case PARTIAL_APPLY_CHURN -> workload + " applied only partially " + count + " times (not"
                    + " restart-free) — in-place resize is being rejected. Verify"
                    + " InPlacePodVerticalScaling is enabled on the cluster.";
            case RESIZE_FLIP_FLOP -> workload + "'s applied CPU request reversed direction " + count
                    + " times across the last applies in " + windowHours + "h — the recommendation is"
                    + " oscillating. Widen the hysteresis band or lengthen the cooldown before"
                    + " re-enabling AUTO.";
            case OOM_EVICTION, HPA_FLAP -> ""; // never emitted in v1
        };
    }

    /**
     * The first recognised emitted token across the matched rows' {@code detail} text, or
     * {@code null} when none is present (so the generic remediation stands). Opaque text is never
     * returned.
     */
    private static String enrichmentToken(List<WorkloadVerticalAction> matched) {
        for (WorkloadVerticalAction row : matched) {
            String detail = row.getDetail();
            if (detail == null || detail.isBlank()) {
                continue;
            }
            // insufficientResource values cpu/memory first — they ride inside the detail prose.
            String lower = detail.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("insufficient cpu") || lower.contains("insufficient-cpu")) {
                return "cpu";
            }
            if (lower.contains("insufficient memory") || lower.contains("insufficient-memory")) {
                return "memory";
            }
            for (String word : detail.split("[^A-Za-z0-9_]+")) {
                if (RECOGNISED_TOKENS.contains(word)) {
                    return word;
                }
            }
        }
        return null;
    }

    private static List<Evidence> evidence(List<WorkloadVerticalAction> rows) {
        return rows.stream()
                .map(r -> new Evidence(r.getAppliedAt(), r.getOutcome(), r.getDetail(),
                        r.getCorrelationId()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    /** The observed {@code afterJson.cpuRequestMillis}, or {@code null} when absent/unparseable. */
    static Long cpuRequestMillis(String afterJson) {
        if (afterJson == null || afterJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(afterJson);
            JsonNode containers = node.isArray() ? node : node.get("containers");
            if (containers == null || !containers.isArray() || containers.isEmpty()) {
                return null;
            }
            // The recorder serialises the first pod's containers in order; the first container is
            // the workload's primary. Take the first container that carries a numeric value.
            for (JsonNode container : containers) {
                JsonNode value = container.get("cpuRequestMillis");
                if (value != null && value.isNumber()) {
                    return value.asLong();
                }
            }
            return null;
        } catch (Exception notJson) {
            return null;
        }
    }

    private static VerticalWorkloadDto.WorkloadRefDto refOf(WorkloadVerticalAction row) {
        return new VerticalWorkloadDto.WorkloadRefDto(row.getNamespace(), row.getWorkloadKind(),
                row.getWorkloadName());
    }

    private static String workloadKey(WorkloadVerticalAction row) {
        return row.getClusterConfigId() + "|" + row.getNamespace() + "|" + row.getWorkloadKind()
                + "|" + row.getWorkloadName();
    }

    private static String workloadKey(VerticalWorkloadDto.WorkloadRefDto ref) {
        return ref.namespace() + "|" + ref.kind() + "|" + ref.name();
    }

    private static Set<String> recognisedTokens() {
        Set<String> tokens = new java.util.HashSet<>();
        for (ReasonCode code : ReasonCode.values()) {
            tokens.add(code.name());
        }
        for (HpaReason reason : HpaReason.values()) {
            tokens.add(reason.name());
        }
        for (ContainerReason reason : ContainerReason.values()) {
            tokens.add(reason.name());
        }
        tokens.add("cpu");
        tokens.add("memory");
        return tokens.stream().collect(Collectors.toUnmodifiableSet());
    }
}
