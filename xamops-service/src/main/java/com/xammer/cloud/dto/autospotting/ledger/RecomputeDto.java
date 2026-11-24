package com.xammer.cloud.dto.autospotting.ledger;

import com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand;

import java.util.List;

/**
 * Response of the recompute endpoint (design-ledger.md §14.1, AC-LED-17). Amounts are plain decimal
 * strings, never JSON numbers. {@code zeroDiff} is true when the entry and the lines that cite it
 * recompute exactly.
 */
public record RecomputeDto(String entryId, String calcVersion, boolean zeroDiff, List<Diff> diffs) {
    /** One differing field; {@code minorUnitDiffers} says whether the difference survives rounding to cents. */
    public record Diff(String scope, String field, String stored, String recomputed, boolean minorUnitDiffers) {}

    public static RecomputeDto from(LedgerRecomputeCommand.Result r) {
        return new RecomputeDto(r.entryId(), r.calcVersion(), r.zeroDiff(), r.diffs().stream()
                .map(d -> new Diff(d.scope(), d.field(), d.stored(), d.recomputed(), d.minorUnitDiffers())).toList());
    }
}
