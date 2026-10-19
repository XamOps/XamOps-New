package com.xammer.cloud.service.automation.workload.rca;

/**
 * The closed taxonomy of root-cause patterns the RCA engine can name over XamOps's durable
 * {@code vertical_workload_action} audit history.
 *
 * <h2>Confidence constants travel on the kind</h2>
 *
 * Each kind carries the two <strong>compile-time</strong> ranking constants {@link #base()} and
 * {@link #perOccurrence()} that define what its confidence number <em>means</em> (design §2.3). They
 * are properties of the structured {@code outcome} signal, not configuration — the per-kind
 * <em>threshold</em> (min-occurrences) is the configurable knob and lives in {@link RcaConfig}. The
 * confidence of a detected pattern is
 * {@code clamp(base + perOccurrence * (occurrences - threshold), 0, 1)}.
 *
 * <h2>Five emitted kinds, two declared dependencies</h2>
 *
 * The five emitted kinds are derivable from durable columns alone and are produced by
 * {@link RcaAnalyzer} today. {@link #OOM_EVICTION} and {@link #HPA_FLAP} are declared so the taxonomy
 * is complete and stable for a later task, but are <strong>never emitted in v1</strong>: each needs a
 * data source XamOps does not yet persist (Kubernetes {@code Warning} events, and per-action HPA
 * replica history). They are surfaced honestly as dependencies rather than faked. Their ranking
 * constants are left at {@code 0} because no occurrence of them is ever scored in v1.
 */
public enum RcaPatternKind {

    /** Repeated {@code FAILED} applies on one workload. */
    REPEATED_FAILED(0.40, 0.15),

    /** Repeated gate-blocks: safety rules declining the apply. */
    REPEATED_REFUSED(0.50, 0.10),

    /** Repeated {@code PARTIALLY_APPLIED} / not-restart-free applies. */
    PARTIAL_APPLY_CHURN(0.40, 0.15),

    /** Repeated {@code EXPIRED} applies: commands never landing in time. */
    EXPIRY_CHURN(0.40, 0.15),

    /** Sign reversals of the observed {@code cpuRequestMillis} trend across consecutive applies. */
    RESIZE_FLIP_FLOP(0.60, 0.10),

    /**
     * OOM / eviction patterns. <strong>Declared, never emitted in v1.</strong> Needs Kubernetes
     * {@code Warning} events persisted server-side; see {@link RcaAnalyzer} and the design §2.1
     * dependencies.
     */
    OOM_EVICTION(0.0, 0.0),

    /**
     * HPA replica flapping. <strong>Declared, never emitted in v1.</strong> Needs per-action HPA
     * replica-change history persisted; {@code HpaView.replicaChangeCount} is read-through-cluster
     * only.
     */
    HPA_FLAP(0.0, 0.0);

    private final double base;
    private final double perOccurrence;

    RcaPatternKind(double base, double perOccurrence) {
        this.base = base;
        this.perOccurrence = perOccurrence;
    }

    /** The confidence floor a single at-threshold detection carries. */
    public double base() {
        return base;
    }

    /** The confidence added per occurrence above the threshold. */
    public double perOccurrence() {
        return perOccurrence;
    }
}
