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

    /**
     * Every switch row, newest change first — the operator {@code GET /switches} read (design-core.md
     * §6.6). The property value and the effective value are computed in the service layer; this
     * returns the stored DB half ({@code engaged}, {@code reason}, {@code changed_by/at},
     * {@code version}).
     */
    @Query("select s from AutoSpottingSwitch s order by s.changedAt desc, s.scopeKey asc")
    List<AutoSpottingSwitch> findAllByOrderByChangedAtDesc();

    /**
     * Guarded update of an existing switch row for the operator {@code PUT /switches/{scopeKey}}
     * (design-core.md §6.6). Sets {@code engaged}, {@code reason}, {@code changed_by/at} and bumps
     * {@code version}, but only when the stored {@code version} matches {@code expectedVersion}, so
     * two racing operators can't both win (409 {@code SWITCH_VERSION_MISMATCH}). Returns rows updated
     * (1 on success, 0 on a version mismatch).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update AutoSpottingSwitch s set s.engaged = :engaged, s.reason = :reason,"
            + " s.changedBy = :changedBy, s.changedAt = :changedAt, s.version = s.version + 1"
            + " where s.scopeKey = :scopeKey and s.version = :expectedVersion")
    int updateGuarded(@org.springframework.data.repository.query.Param("scopeKey") String scopeKey,
                      @org.springframework.data.repository.query.Param("engaged") boolean engaged,
                      @org.springframework.data.repository.query.Param("reason") String reason,
                      @org.springframework.data.repository.query.Param("changedBy") String changedBy,
                      @org.springframework.data.repository.query.Param("changedAt") java.time.Instant changedAt,
                      @org.springframework.data.repository.query.Param("expectedVersion") long expectedVersion);
}
