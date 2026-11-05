package com.xammer.cloud.service.autospotting.core.reconcile;

import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.autospotting.ReconcilerStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The DR-23 sweep cap and order (design-core.md §5.1, plan-phase-08.md task 8.1). A sweep takes at
 * most {@code autospotting.reconciler.max-groups-per-sweep} (default 200) groups of a provider, in
 * this order:
 *
 * <ol>
 *   <li>groups with a due hint ({@code reconcile_hint_at} set: an event, a switch change, a
 *       compensation candidate);</li>
 *   <li>managed groups (ACTIVE, DEGRADED, FALLBACK, PAUSED, APPLYING, REVERTING) by oldest
 *       {@code last_reconciled_at};</li>
 *   <li>the rest (PLANNED) by oldest {@code last_reconciled_at}.</li>
 * </ol>
 *
 * <p>Groups past the cap wait for the next sweep, so the oldest go first and none starves. The
 * ordering itself is the {@link ReconcilerStore#sweepOrder} SQL; this component owns the cap value.
 */
@Component
public class SweepOrder {

    private final ReconcilerStore store;
    private final int maxGroupsPerSweep;

    public SweepOrder(ReconcilerStore store,
                      @Value("${autospotting.reconciler.max-groups-per-sweep:200}") int maxGroupsPerSweep) {
        this.store = store;
        this.maxGroupsPerSweep = maxGroupsPerSweep;
    }

    /** The capped, DR-23-ordered group ids to sweep for {@code provider} (§5.1). */
    public List<UUID> forProvider(Provider provider) {
        return store.sweepOrder(provider, maxGroupsPerSweep);
    }

    /** The per-sweep cap (§5.1, default 200). */
    public int cap() {
        return maxGroupsPerSweep;
    }
}
