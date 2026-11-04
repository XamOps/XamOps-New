package com.xammer.cloud.service.automation.workload;

/**
 * Who initiated an apply: a human pressing Apply, or the (not-yet-built) scheduled loop.
 *
 * <p>Stored as text on {@code vertical_workload_action.trigger_source} and defaulted to
 * {@link #MANUAL} on every path that exists today, because the only caller of
 * {@code VerticalApplyService.apply} is endpoint 3, the user-initiated apply. FEAT-002's scheduled
 * loop is the one caller that will pass {@link #AUTO}.
 */
public enum ActionTrigger {

    /** A human pressed Apply. The only trigger any shipped path produces. */
    MANUAL,

    /** The scheduled auto-apply loop (FEAT-002) applied without a human naming the numbers. */
    AUTO,
    /**
     * Manual mode's probe (Phase B): a dry run the proposer makes to classify a workload resize and
     * gather its proof. Writes nothing, records no refusal and no attempt, so a 15-minute proposal
     * cycle does not flood the activity log.
     */
    PROPOSAL,
    /**
     * A human approved a Manual-mode proposal and this is the real apply. Records like
     * {@link #MANUAL} (a refusal is logged), and bypasses no gate: the full apply chain runs.
     */
    APPROVED
}
