package com.xammer.cloud.service.autospotting.core.switches;

import com.xammer.cloud.repository.autospotting.AutoSpottingAllowlistEntryRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingSwitchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the DB half of the kill-switch hierarchy (design-core.md §6.1, plan-phase-05.md task 5.1)
 * and caches it per instance with a 15 s refresh, so a change reaches every instance within 60 s
 * (AC-SPN-05). {@link #current()} serves the cached {@link SwitchState} for request-path gate
 * checks; {@link #readUncached()} bypasses the cache and reads the two stores straight through, for
 * a runner at every step boundary and for the reconciler's pause sweep (GR-06; used in Phase 08).
 *
 * <p>The effective value of a switch is {@code property AND NOT db.engaged}: the DB can only
 * tighten (FR-SPN-09). This service owns only the DB half; the property half lives in
 * {@link AutoSpottingSwitchProperties}, {@link com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags}
 * and (row a) {@code autospotting.enabled}. {@link ActionGate} combines the two.
 *
 * <p><strong>Fail closed, per store.</strong> An unreadable {@code autospotting_switch} table makes
 * {@link SwitchState#switchStoreUnreadable} true, so switches (a) to (e) count as engaged and every
 * mutation stops while reads continue; an unreadable {@code autospotting_allowlist} table makes
 * {@link SwitchState#allowlistUnreadable} true and the allowlist empty, so no account is
 * allowlisted. The two stores are read independently, so one being down doesn't mask the other.
 */
@Service
public class SwitchService {

    private static final Logger log = LoggerFactory.getLogger(SwitchService.class);

    private final AutoSpottingSwitchRepository switches;
    private final AutoSpottingAllowlistEntryRepository allowlist;
    private final Duration refreshInterval;

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public SwitchService(AutoSpottingSwitchRepository switches,
                         AutoSpottingAllowlistEntryRepository allowlist,
                         @Value("${autospotting.switch.cache-refresh-seconds:15}") long cacheRefreshSeconds) {
        this.switches = switches;
        this.allowlist = allowlist;
        this.refreshInterval = Duration.ofSeconds(cacheRefreshSeconds);
    }

    /**
     * The cached switch state, refreshed at most once per {@code autospotting.switch.cache-refresh-seconds}
     * (default 15 s). A refresh never throws: each store read fails closed on its own, so a refresh
     * always yields a usable (possibly tightened) snapshot that replaces the previous one.
     */
    public SwitchState current() {
        Cached snapshot = cache.get();
        long now = System.nanoTime();
        if (snapshot == null || now - snapshot.readAtNanos >= refreshInterval.toNanos()) {
            SwitchState fresh = readUncached();
            cache.set(new Cached(fresh, now));
            return fresh;
        }
        return snapshot.state;
    }

    /**
     * Read both stores straight through, no cache (GR-06). Used at every step boundary and by the
     * reconciler's pause step. Each store fails closed independently.
     */
    public SwitchState readUncached() {
        Set<String> engaged;
        boolean switchUnreadable;
        try {
            engaged = new HashSet<>(switches.findEngagedScopeKeys());
            switchUnreadable = false;
        } catch (RuntimeException e) {
            engaged = Set.of();
            switchUnreadable = true;
            log.warn("autospotting_switch unreadable; failing closed (switches a-e engaged): {}", e.toString());
        }

        Set<Long> allowed;
        boolean allowlistUnreadable;
        try {
            allowed = new HashSet<>(allowlist.findAllowedCloudAccountIds());
            allowlistUnreadable = false;
        } catch (RuntimeException e) {
            allowed = Set.of();
            allowlistUnreadable = true;
            log.warn("autospotting_allowlist unreadable; failing closed (no account allowlisted): {}", e.toString());
        }

        return new SwitchState(engaged, allowed, switchUnreadable, allowlistUnreadable);
    }

    private record Cached(SwitchState state, long readAtNanos) {
    }
}
