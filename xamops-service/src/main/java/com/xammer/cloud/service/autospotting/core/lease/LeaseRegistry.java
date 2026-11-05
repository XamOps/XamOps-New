package com.xammer.cloud.service.autospotting.core.lease;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each instance's in-memory map from group id to the {@link LeaseHandle} it currently holds
 * (design-core.md §3.3). It is a guard against self-contention, not a correctness mechanism:
 * correctness still comes from the guarded native statements in {@link LeaseRepository}.
 *
 * <ul>
 *   <li>The dispatcher reuses its own handle to resume a WAITING op it already holds, with no new
 *       acquire, so the generation doesn't move.</li>
 *   <li>Every other local component consults the registry before the DB: the reconciler treats
 *       "held locally" like "held elsewhere" and runs only the read-only health pass; an API or
 *       EBS-automation path that needs the lease returns 409 {@code AS_GROUP_BUSY}.</li>
 * </ul>
 *
 * <p>Acquire is not re-entrant: two components on the same instance (own tokens) contend exactly
 * like two instances. The registry only records which handle, if any, this instance holds.
 */
@Component
public class LeaseRegistry {

    private final Map<UUID, LeaseHandle> held = new ConcurrentHashMap<>();

    /** The handle this instance holds for {@code groupId}, if any. */
    public Optional<LeaseHandle> find(UUID groupId) {
        return Optional.ofNullable(held.get(groupId));
    }

    /** Whether this instance holds a (non-lost) lease on {@code groupId}. */
    public boolean isHeldLocally(UUID groupId) {
        LeaseHandle handle = held.get(groupId);
        return handle != null && !handle.isLost();
    }

    public void register(UUID groupId, LeaseHandle handle) {
        held.put(groupId, handle);
    }

    public void remove(UUID groupId) {
        held.remove(groupId);
    }

    /** Snapshot of every group handle this instance holds (used by graceful shutdown). */
    public Map<UUID, LeaseHandle> snapshot() {
        return Map.copyOf(held);
    }
}
