package com.xammer.cloud.service.autospotting.spi;

import java.util.List;

/**
 * An ordered, deterministic list of {@link Step}s computed from the plan body and snapshot
 * (design-core.md §9.1). Persisting {@code step_seq} plus re-deriving the program from immutable
 * inputs is enough to resume on any instance: the step at index {@code seq} is always the same step.
 */
public record StepProgram(List<Step> steps) {

    public StepProgram {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public int size() {
        return steps.size();
    }

    /** The step at {@code seq} (0-based), matching {@code spot_operation.step_seq}. */
    public Step at(int seq) {
        return steps.get(seq);
    }
}
