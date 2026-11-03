package com.xammer.cloud.dto.autospotting.v2;

import java.util.List;
import java.util.Map;

/**
 * The AutoSpotting runtime configuration the SPA reads from {@code GET /api/autospotting/v2/config}
 * (design-ux-ops.md §2.1, §5.2, §10.2; plan-phase-09.md task 9.3). It lets the shell decide what to
 * render without a frontend rebuild: whether the shared shell is on, each provider's readiness, and
 * the current dry-run and engaged-switch state.
 *
 * @param shellEnabled whether the shared shell is on ({@code autospotting.ui.shell-enabled}, default
 *                     false). With it false the legacy pages render as today (FR-ROL-01)
 * @param dryRun       the effective {@code autospotting.mutations.dry-run}
 * @param providers    per-provider readiness, keyed {@code "AWS"}/{@code "GCP"}
 * @param engagedSwitches the engaged DB switch scope keys (so the UI can name why a mutation is off)
 */
public record ConfigDto(
        boolean shellEnabled,
        boolean dryRun,
        Map<String, ProviderConfig> providers,
        List<String> engagedSwitches) {

    public ConfigDto {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        engagedSwitches = engagedSwitches == null ? List.of() : List.copyOf(engagedSwitches);
    }

    /**
     * One provider's readiness (design-core.md §6.1 flags). {@code enabled} is the per-cloud master
     * ({@code AND NOT db.engaged}); {@code conversionsEnabled} the conversions flag; {@code allowlistOnly}
     * the lift gate; {@code v2MutationsEnabled} whether the provider's restore program is live;
     * {@code beta} the PO-25 label until that cloud's lift criteria and one shadow month are met.
     */
    public record ProviderConfig(
            boolean enabled,
            boolean conversionsEnabled,
            boolean allowlistOnly,
            boolean v2MutationsEnabled,
            boolean beta) {
    }
}
