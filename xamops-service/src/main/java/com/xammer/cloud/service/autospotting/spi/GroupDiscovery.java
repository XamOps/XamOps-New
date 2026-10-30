package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.core.discovery.DiscoveredGroup;

/**
 * The read-only discovery SPI (design-core.md §2.5, plan-phase-08.md task 8.3). One implementation
 * per cloud (AWS in Phase 12, GCP in Phase 26); a fake discovery stands in until then.
 *
 * <p>Discovery reads whole accounts and classifies groups by the first matching rule of §2.5. It is
 * a <strong>separate SPI type</strong> from {@link SpotProvider} on purpose: the classification
 * rules read whole accounts rather than one group, and must never share a code path with mutation.
 * Like planning ({@link SpotProvider#createPlan}), discovery takes a {@link ReadOnlyCloudView}, so a
 * mutating call cannot compile here (AC-PLN-01, enforced structurally by
 * {@code AutoSpottingArchitectureTest}).
 *
 * <p>{@link #discover} returns one {@link DiscoveredGroup} per group it classified under rules 1-3
 * (rule 4 {@code EXTERNAL_SPOT} is a read-time class with no row, and rule 5 produces nothing), each
 * tagged with the §2.5 rule number that matched and a reconstructed snapshot for the LEGACY_UNCONFIRMED
 * rows. Locations (regions, projects) that could not be read are reported in
 * {@link DiscoveredGroup.Result#failedLocations()}, never swallowed as "no groups" (FR-OBS-06).
 *
 * <p>Dark by default: no {@code GroupDiscovery} bean exists in production yet, so the discovery job
 * finds no provider to run and does nothing in any environment. Implementations make no mutating
 * cloud call.
 */
public interface GroupDiscovery {

    /** Which cloud this discovery serves. */
    com.xammer.cloud.domain.autospotting.Provider provider();

    /**
     * Discover and classify the account's groups through {@code view} (reads only). Returns the
     * classified groups (rules 1-3), the per-rule counts, and the locations that could not be read.
     * Never throws for an unreadable location: it is reported, not treated as "no groups" (§2.5).
     */
    DiscoveredGroup.Result discover(CloudAccount account, ReadOnlyCloudView view);
}
