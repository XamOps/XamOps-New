package com.xammer.cloud.service.automation.workload;

/**
 * How a native Kubernetes VPA, if any, coexists with this workload — the input to the symmetric VPA
 * rule in {@link WorkloadAutoscalingEngine#reconcile}.
 *
 * <p>Model A (the owner's choice, design §5): XamOps's own engine is the vertical autoscaler and the
 * native VPA is a coexistence concern only. A <em>mutating</em> native VPA writes pod requests, so
 * the engine must refuse to fight it; an {@code Off} VPA changes nothing and is shown side by side.
 * Ownership that could not be read is treated as hostile — the fail-closed reading — so the engine
 * yields rather than guesses.
 */
public enum NativeVpaMode {

    /** No native VPA targets this workload. Vertical proceeds normally. */
    NONE,

    /** A native VPA exists in {@code updateMode: Off} (recommend-only): it never changes pods. */
    OFF,

    /**
     * A native VPA in a mutating mode ({@code Initial}, {@code Recreate}, {@code Auto},
     * {@code InPlaceOrRecreate}; an absent {@code updateMode} reads as {@code Auto}). Vertical
     * refuses the whole workload ({@link HpaReason#VPA_MANAGED}).
     */
    MUTATING,

    /**
     * VPA ownership was unreadable (RBAC 403 / unreachable API). Fail closed: vertical yields
     * ({@link HpaReason#VPA_NOT_READABLE}), unknown ownership treated as hostile ownership.
     */
    UNREADABLE;

    /** Whether this mode makes vertical refuse the workload. */
    public boolean refusesVertical() {
        return this == MUTATING || this == UNREADABLE;
    }
}
