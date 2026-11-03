package com.xammer.cloud.service.autospotting.core.reconcile;

import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import org.springframework.stereotype.Component;

/**
 * The GONE check (design-core.md §5.2 DR-7, plan-phase-08.md task 8.2). A group is declared GONE
 * only after two NOT_FOUND results two sweeps apart, never on a single 404 or a permission error.
 * A permission error sets {@code last_error_code=PERMISSION} and never counts as GONE.
 *
 * <p>State across sweeps is tracked on the group's {@code last_error_code}: a first NOT_FOUND stamps
 * {@code NOT_FOUND_ONCE}; a second NOT_FOUND with that marker already present declares GONE; any
 * FOUND or PERMISSION clears the marker. A single NOT_FOUND never declares GONE (AC-CMP-02, C-04).
 *
 * <p>This is a pure decision: the sweep reads {@code getStatus}, calls this, and applies the
 * result. No I/O here.
 */
@Component
public class GoneDetector {

    /** The marker written to {@code last_error_code} after the first NOT_FOUND sweep. */
    public static final String NOT_FOUND_ONCE = "NOT_FOUND_ONCE";

    /** The result of one GONE check on a group's live status. */
    public enum Verdict {
        /** The group is present. Clear any prior NOT_FOUND marker. */
        FOUND,
        /** First NOT_FOUND sweep: record the marker, do not declare GONE yet. */
        NOT_FOUND_FIRST,
        /** Second NOT_FOUND: the group is GONE from any state that has the edge (§2.2). */
        GONE,
        /** Permission error: never counts as GONE; sets last_error_code=PERMISSION and alerts. */
        PERMISSION
    }

    /**
     * Evaluate the DR-7 GONE check for one sweep.
     *
     * @param group             the current group row (for the prior {@code last_error_code})
     * @param status            the live status from {@code getStatus} (null when the call itself failed)
     * @param permissionError   true when the provider reported a permission error (never GONE)
     * @return the GONE verdict
     */
    public Verdict check(SpotGroup group, GroupStatus status, boolean permissionError) {
        if (permissionError) {
            return Verdict.PERMISSION;
        }
        if (status == null || !status.found()) {
            // NOT_FOUND: check whether a prior sweep already saw NOT_FOUND (the two-sweep rule).
            return NOT_FOUND_ONCE.equals(group.getLastErrorCode())
                    ? Verdict.GONE
                    : Verdict.NOT_FOUND_FIRST;
        }
        return Verdict.FOUND;
    }
}
