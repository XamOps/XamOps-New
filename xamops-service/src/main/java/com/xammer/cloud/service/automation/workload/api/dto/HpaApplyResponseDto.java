package com.xammer.cloud.service.automation.workload.api.dto;

/**
 * The HPA apply endpoint's {@code 202} body, modelled on {@link ApplyResponseDto}.
 *
 * <p>The first three fields are the published contract and are <strong>verbatim</strong>, so the UI
 * and a parallel workflow stay built against the same shape as the vertical apply:
 *
 * <pre>{@code { "correlationId": "c0ffee…", "state": "ACCEPTED", "dryRun": true } }</pre>
 *
 * <h2>{@code applied} is additive, and it is real</h2>
 *
 * The contract's shape is asynchronous by design — the primary execution path is a command queue the
 * in-cluster agent drains, so an outcome genuinely arrives later. The <em>direct</em> path completes
 * inside the request, so the server echoes the bounds it actually patched (or would have patched, on
 * a dry run) rather than withholding what it already knows. {@code applied} carries the final HPA
 * numbers the patch set, so a reader can check the claim rather than take it; it is {@code null} when
 * the command was refused or expired, never a guess.
 *
 * @param correlationId the idempotency key, echoed
 * @param state         {@code ACCEPTED | DUPLICATE | REFUSED}
 * @param dryRun        echoed from the request; always present, never defaulted
 * @param applied       the bounds/target the patch set, or null when nothing was written
 */
public record HpaApplyResponseDto(String correlationId, String state, boolean dryRun, Applied applied) {

    /**
     * What the HPA's bounds and target became — or, on a dry run, would become.
     *
     * @param minReplicas          the resulting {@code spec.minReplicas}, or null if left alone
     * @param maxReplicas          the resulting {@code spec.maxReplicas}, or null if left alone
     * @param targetUtilizationPct the resulting CPU {@code averageUtilization}, or null if left alone
     * @param dryRun               whether this was a rehearsal; echoed so an audit entry needs no join
     */
    public record Applied(Integer minReplicas, Integer maxReplicas, Integer targetUtilizationPct,
                          boolean dryRun) {
    }

    public static HpaApplyResponseDto accepted(String correlationId, String state, boolean dryRun,
                                               Integer minReplicas, Integer maxReplicas,
                                               Integer targetUtilizationPct) {
        return new HpaApplyResponseDto(correlationId, state, dryRun,
                new Applied(minReplicas, maxReplicas, targetUtilizationPct, dryRun));
    }

    /** A submission with no write to report (a duplicate replay or a refusal). */
    public static HpaApplyResponseDto of(String correlationId, String state, boolean dryRun) {
        return new HpaApplyResponseDto(correlationId, state, dryRun, null);
    }
}
