package com.xammer.cloud.service.autospotting.spi;

/**
 * One step of a conversion or restore program (design-core.md §9.1). Each step is idempotent or
 * check-before-act (§9.2): {@link #execute} does the work with the context's deterministic token,
 * {@link #probe} reads the cloud to decide whether this step's side effect already happened (used by
 * a resumer that finds {@code INTENT_RECORDED}, §4.5), and {@link #describeIntent} returns the
 * dry-run text.
 */
public interface Step {

    /** The step name, written to {@code spot_operation_step.name} (e.g. {@code PERSIST_SNAPSHOT}). */
    String name();

    /** Do the step's work. Before any mutating cloud call it asserts the lease is held (§3.4). */
    StepOutcome execute(StepContext ctx);

    /** Read-only: did this step's side effect happen? Used to resume an {@code INTENT_RECORDED} step. */
    StepOutcome probe(StepContext ctx);

    /** Dry-run text describing what {@link #execute} would do, without doing it. */
    String describeIntent(StepContext ctx);
}
