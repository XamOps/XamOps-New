package com.xammer.cloud.service.autospotting.spi;

/**
 * The three evaluation modes an eligibility evaluator runs in (design-core.md §9.2). The same rule
 * codes and result shape are returned in every mode (AC-ELG-05).
 *
 * <ul>
 *   <li>{@link #LIST}: group list, list KPIs and dashboard potential — checks answerable from the
 *       list's own describe plus cached expensive checks; anything else is {@code DEFERRED}.</li>
 *   <li>{@link #PLAN}: plan creation — every check, fresh.</li>
 *   <li>{@link #CONFIRM}: op step 0 ({@code VERIFY_PRECONDITIONS}) — every check, fresh.</li>
 * </ul>
 */
public enum EvalMode {
    LIST,
    PLAN,
    CONFIRM
}
