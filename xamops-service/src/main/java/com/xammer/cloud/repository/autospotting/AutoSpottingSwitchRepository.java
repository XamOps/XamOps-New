package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.AutoSpottingSwitch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Finders for {@link AutoSpottingSwitch} (plan-phase-03.md task 3.2, plan-phase-05.md task 5.1),
 * keyed by the scope key. {@link com.xammer.cloud.service.autospotting.core.switches.SwitchService}
 * reads the engaged scope keys (its 15 s cache and {@code readUncached()} both go through
 * {@link #findEngagedScopeKeys()}); the operator API's writes arrive in Phase 09.
 */
@Repository
public interface AutoSpottingSwitchRepository extends JpaRepository<AutoSpottingSwitch, String> {

    /**
     * Just the scope keys of the currently-engaged rows. The effective value of a switch is
     * {@code property AND NOT db.engaged} (design-core.md §6.1), so the DB override is a set of
     * engaged scopes. A read failure must surface so {@code SwitchService} can fail closed: this
     * query throws on an unreadable {@code autospotting_switch} table rather than returning empty.
     */
    @Query("select s.scopeKey from AutoSpottingSwitch s where s.engaged = true")
    List<String> findEngagedScopeKeys();

    /**
     * The engaged rows whose scope key is one of {@code scopeKeys}, with their {@code changed_at}.
     * The reconciler's switch-pause step (design-core.md §6.4) clamps the pause transition's
     * {@code effective_at} to {@code GREATEST(switch.changed_at, latest transition)}, so billing
     * stops when the switch was engaged, not at the sweep (GR-06, ledger §6.2). Read inside the
     * caller's transaction so the engaged set and the fenced transition write agree.
     */
    @Query("select s from AutoSpottingSwitch s where s.engaged = true and s.scopeKey in :scopeKeys")
    List<AutoSpottingSwitch> findEngagedByScopeKeys(
            @org.springframework.data.repository.query.Param("scopeKeys") List<String> scopeKeys);
}
