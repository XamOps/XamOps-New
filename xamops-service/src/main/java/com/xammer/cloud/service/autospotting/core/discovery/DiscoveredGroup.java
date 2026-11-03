package com.xammer.cloud.service.autospotting.core.discovery;

import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;

import java.util.List;

/**
 * One group a {@link com.xammer.cloud.service.autospotting.spi.GroupDiscovery} classified under the
 * §2.5 rules, plus the run-level {@link Result} the discovery returns (design-core.md §2.5,
 * plan-phase-08.md task 8.3).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code groupKey} / {@code providerStableId} — the group's identity (AWS {@code region/asgName},
 *       GCP {@code project/location/migName}; stable id is the ASG ARN or MIG numeric id).</li>
 *   <li>{@code matchedRule} — the §2.5 rule number that matched (1-5).</li>
 *   <li>{@code reason} — the discovery reason for a LEGACY_UNCONFIRMED row ({@code ORPHAN_MARKER}
 *       for rule 3), or null.</li>
 *   <li>{@code reconstructedSnapshot} — the {@code exact=false} snapshot reconstructed for a
 *       LEGACY_UNCONFIRMED row (rules 2-3), or null for a mere identity match (rule 1).</li>
 *   <li>{@code desiredConfigJson} — the seeded {@code desired_config} (AWS reads the legacy
 *       {@code asg_spot_config} table read-only), or null.</li>
 *   <li>{@code hasMarker} / {@code hasSpotShare} — whether the group still carries a XamOps marker
 *       or a Spot share this run; the engine moves a LEGACY_UNCONFIRMED row with neither on two
 *       consecutive runs to UNMANAGED (§2.5).</li>
 * </ul>
 */
public record DiscoveredGroup(
        String groupKey,
        String providerStableId,
        int matchedRule,
        String reason,
        SnapshotPayload reconstructedSnapshot,
        String desiredConfigJson,
        boolean hasMarker,
        boolean hasSpotShare) {

    /** Reason for an orphan-marker LEGACY_UNCONFIRMED row (§2.5 rule 3). */
    public static final String ORPHAN_MARKER = "ORPHAN_MARKER";

    /**
     * The outcome of one discovery run for an account: the classified groups (rules 1-3), the
     * per-rule counts, and the locations that could not be read (reported, never "no groups").
     *
     * @param provider        the cloud discovered
     * @param groups          groups classified under rules 1-3 (rule 4 is a read-time class, rule 5 none)
     * @param ruleCounts      count per rule number, for the {@code DISCOVERY_RUN} audit record
     * @param failedLocations regions or projects that could not be read (FR-OBS-06)
     */
    public record Result(
            Provider provider,
            List<DiscoveredGroup> groups,
            java.util.Map<Integer, Integer> ruleCounts,
            List<String> failedLocations) {

        public Result {
            groups = groups == null ? List.of() : List.copyOf(groups);
            ruleCounts = ruleCounts == null ? java.util.Map.of() : java.util.Map.copyOf(ruleCounts);
            failedLocations = failedLocations == null ? List.of() : List.copyOf(failedLocations);
        }
    }
}
