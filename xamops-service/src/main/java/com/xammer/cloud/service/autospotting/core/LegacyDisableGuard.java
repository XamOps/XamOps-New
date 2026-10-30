package com.xammer.cloud.service.autospotting.core;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The Phase A disable rule between phases (design-core.md §7.4 "The Phase A disable between phases",
 * A-02, AC-PLN-07; plan-phase-08.md task 8.4). The legacy disable endpoints call this <em>first</em>:
 *
 * <ul>
 *   <li>A group whose {@code spot_group} row is in a v2 state ({@link #V2_STATES}: PLANNED, APPLYING,
 *       ACTIVE, DEGRADED, FALLBACK, PAUSED, REVERTING, FAILED) gets 409 {@code PLAN_REQUIRED} with a
 *       {@code planUrl}, and the cloud is never touched.</li>
 *   <li>A LEGACY_UNCONFIRMED group, or one with no row, keeps working while that cloud's
 *       {@code v2-mutations-enabled} is false. When a row exists, the call takes the group lease so
 *       it can't race a v2 operation (409 {@code AS_GROUP_BUSY} if the lease is held).</li>
 *   <li>Once {@code v2-mutations-enabled} is true for that cloud (B5 for AWS, B6 for GCP), every
 *       group of that cloud gets {@code PLAN_REQUIRED}: the v2 restore program replaces the legacy
 *       disable, so disabling always has a working path.</li>
 * </ul>
 *
 * <p>UNMANAGED, REVERTED and GONE rows are treated like no row (bookkeeping, not v2-managed): the
 * legacy disable keeps working on them while the flag is off (FR-CMP-05).
 *
 * <p>The caller wraps the disable in a try/finally and {@link Guard#close() closes} the returned
 * {@link Guard}, which releases the lease if one was taken. The guard is a safety gate only; it
 * mutates no cloud and, with every cloud's {@code v2-mutations-enabled} false by default, changes no
 * behaviour for a group that has no v2 row — which is every group until a conversion runs (dark).
 */
@Component
public class LegacyDisableGuard {

    private static final Logger log = LoggerFactory.getLogger(LegacyDisableGuard.class);

    /** The v2 states that force {@code PLAN_REQUIRED} on a legacy disable (§7.4). */
    public static final Set<GroupState> V2_STATES = EnumSet.of(
            GroupState.PLANNED, GroupState.APPLYING, GroupState.ACTIVE, GroupState.DEGRADED,
            GroupState.FALLBACK, GroupState.PAUSED, GroupState.REVERTING, GroupState.FAILED);

    private final SpotGroupRepository groups;
    private final AutoSpottingSwitchProperties switchProperties;
    private final LeaseService leaseService;
    private final LeaseRegistry leaseRegistry;

    public LegacyDisableGuard(SpotGroupRepository groups, AutoSpottingSwitchProperties switchProperties,
                              LeaseService leaseService, LeaseRegistry leaseRegistry) {
        this.groups = groups;
        this.switchProperties = switchProperties;
        this.leaseService = leaseService;
        this.leaseRegistry = leaseRegistry;
    }

    /**
     * Gate a legacy disable of the group identified by {@code (provider, cloudAccountId, groupKey)}.
     * Throws {@link AutoSpottingBlockedException} with {@code PLAN_REQUIRED} or {@code AS_GROUP_BUSY}
     * when the legacy path must not run; otherwise returns a {@link Guard} the caller must close.
     *
     * @param planUrl the v2 Revert URL surfaced in a {@code PLAN_REQUIRED} refusal
     */
    public Guard beforeDisable(Provider provider, Long cloudAccountId, String groupKey, String planUrl) {
        boolean aws = provider == Provider.AWS;

        // Once v2 mutations are enabled for the cloud, every group routes through the v2 restore (§7.4).
        if (switchProperties.v2MutationsEnabled(aws)) {
            throw AutoSpottingBlockedException.planRequired(planUrl);
        }

        SpotGroup row = findLiveRow(provider, cloudAccountId, groupKey);
        if (row == null) {
            // No v2 row: Phase A behaviour, unchanged (FR-CMP-05). No lease to take.
            return Guard.noLease();
        }
        if (V2_STATES.contains(row.getState())) {
            // A v2-managed group: the legacy disable must not run (§7.4).
            throw AutoSpottingBlockedException.planRequired(planUrl);
        }
        // A LEGACY_UNCONFIRMED (or other non-v2 bookkeeping) row exists: take the group lease for the
        // call so it can't race a v2 op (§7.4, §3.3). The registry guards self-contention.
        if (leaseRegistry.isHeldLocally(row.getId())) {
            throw AutoSpottingBlockedException.groupBusy();
        }
        Optional<LeaseHandle> lease = leaseService.acquireGroup(row.getId(), LeaseService.Component.API);
        if (lease.isEmpty()) {
            throw AutoSpottingBlockedException.groupBusy();
        }
        if (log.isDebugEnabled()) {
            log.debug("legacy disable gated for group {} ({}): lease held for the call", row.getId(),
                    row.getState());
        }
        return Guard.withLease(leaseService, lease.get());
    }

    /** The live {@code spot_group} row for a key (UNMANAGED/REVERTED/GONE are not live), or null. */
    private SpotGroup findLiveRow(Provider provider, Long cloudAccountId, String groupKey) {
        if (cloudAccountId == null || groupKey == null) {
            return null;
        }
        return groups.findByProviderAndCloudAccountIdAndGroupKeyAndStateNot(
                        provider, cloudAccountId, groupKey, GroupState.GONE)
                .filter(g -> g.getState() != GroupState.UNMANAGED && g.getState() != GroupState.REVERTED)
                .orElse(null);
    }

    /**
     * A held gate around one legacy-disable call. {@link #close()} releases the lease if one was
     * taken; it is a no-op when no row existed. Use in a try/finally around the disable.
     */
    public static final class Guard implements AutoCloseable {

        private final LeaseService leaseService;
        private final LeaseHandle handle;

        private Guard(LeaseService leaseService, LeaseHandle handle) {
            this.leaseService = leaseService;
            this.handle = handle;
        }

        /** A gate that holds no lease (no v2 row existed); closing it is a no-op. */
        public static Guard noLease() {
            return new Guard(null, null);
        }

        static Guard withLease(LeaseService leaseService, LeaseHandle handle) {
            return new Guard(leaseService, handle);
        }

        @Override
        public void close() {
            if (leaseService != null && handle != null) {
                leaseService.releaseGroup(handle);
            }
        }
    }
}
