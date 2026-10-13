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
    AUTO
}
