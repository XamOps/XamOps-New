package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.PauseReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * The native guarded lifecycle statements of design-core.md §2.3 and §2.4's column ownership.
 * JdbcTemplate only, like {@link LeaseRepository}: the {@code spot_group} entity is
 * {@code @Immutable}, so JPA never writes its lifecycle columns — these guarded native UPDATEs own
 * them.
 *
 * <p>Every method runs {@link Propagation#MANDATORY}: it must join the caller's already-open
 * transaction, whose first statement is either the lease fence
 * ({@link com.xammer.cloud.service.autospotting.core.lease.FencedTx}, §3.4) or the plan-driven
 * {@code SELECT ... FOR UPDATE} row lock (§2.3). The transition insert and the audit record are
 * written in that same transaction by {@code GroupLifecycleService}.
 *
 * <p>The lifecycle UPDATE is guarded on {@code state} and {@code version} and always sets
 * {@code version = version + 1} (§2.3). Zero rows updated means the transition did not happen (a
 * stale version or a state that moved); the caller re-reads and never retries blind. Only lifecycle
 * columns are written here (§2.4), and {@code desired_config}/{@code desired_fingerprint} only when
 * the caller passes a config write ({@link #transitionWithConfig}), which the three write points of
 * §2.4 use.
 */
@Repository
public class SpotGroupLifecycleRepository {

    // Guarded lifecycle UPDATE: state + the lifecycle columns, bumping version. Guarded on the
    // expected (state, version). desired_config / desired_fingerprint are left untouched.
    static final String TRANSITION =
            "UPDATE spot_group"
                    + "   SET state = ?, pause_reason = ?, paused_from_state = ?,"
                    + "       config_mode = COALESCE(?, config_mode),"
                    + "       current_operation_id = ?,"
                    + "       state_changed_at = ?, updated_at = ?,"
                    + "       version = version + 1"
                    + " WHERE id = ? AND state = ? AND version = ?";

    // As above, but also writes desired_config / desired_fingerprint (the three §2.4 write points).
    // The ::jsonb cast keeps the column typed when the value is passed as text (or null).
    static final String TRANSITION_WITH_CONFIG =
            "UPDATE spot_group"
                    + "   SET state = ?, pause_reason = ?, paused_from_state = ?,"
                    + "       config_mode = COALESCE(?, config_mode),"
                    + "       desired_config = ?::jsonb, desired_fingerprint = ?,"
                    + "       current_operation_id = ?,"
                    + "       state_changed_at = ?, updated_at = ?,"
                    + "       version = version + 1"
                    + " WHERE id = ? AND state = ? AND version = ?";

    // Plan-driven row lock (§2.3): no lease holder to fence against, so lock the row first with the
    // same (state, version) guard. Returns the version so the caller can issue the guarded UPDATE.
    static final String LOCK_ROW =
            "SELECT version FROM spot_group WHERE id = ? AND state = ? FOR UPDATE";

    // Append-only transition insert (§2.4). effective_at is clamped by the caller to
    // GREATEST(requested, latest effective_at); trg_sgt_monotonic is the backstop (DR-01).
    static final String INSERT_TRANSITION =
            "INSERT INTO spot_group_transition"
                    + " (group_id, from_state, to_state, reason_code, actor_type, actor_id, operation_id,"
                    + "  occurred_at, effective_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    // The group's latest transition effective_at (by id), for the clamp. Null when none exists yet.
    static final String LATEST_EFFECTIVE_AT =
            "SELECT effective_at FROM spot_group_transition WHERE group_id = ? ORDER BY id DESC LIMIT 1";

    private final JdbcTemplate jdbc;

    /** @param dataSource the {@code @Primary} application DataSource */
    public SpotGroupLifecycleRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * The guarded lifecycle UPDATE (§2.3), without touching {@code desired_config}. Returns the
     * number of rows updated: 1 when the transition took effect, 0 when the {@code (state, version)}
     * guard missed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int transition(UUID groupId, GroupState from, long expectedVersion, GroupState to,
                          PauseReason pauseReason, GroupState pausedFromState, ConfigMode configMode,
                          UUID operationId, Instant stateChangedAt) {
        return jdbc.update(TRANSITION,
                to.name(),
                pauseReason == null ? null : pauseReason.name(),
                pausedFromState == null ? null : pausedFromState.name(),
                configMode == null ? null : configMode.name(),
                operationId,
                Timestamp.from(stateChangedAt),
                Timestamp.from(stateChangedAt),
                groupId, from.name(), expectedVersion);
    }

    /**
     * The guarded lifecycle UPDATE that also writes {@code desired_config} and
     * {@code desired_fingerprint} — used only at the three write points of §2.4. Returns rows
     * updated (1 or 0) like {@link #transition}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int transitionWithConfig(UUID groupId, GroupState from, long expectedVersion, GroupState to,
                                    PauseReason pauseReason, GroupState pausedFromState, ConfigMode configMode,
                                    UUID operationId, Instant stateChangedAt,
                                    String desiredConfig, String desiredFingerprint) {
        return jdbc.update(TRANSITION_WITH_CONFIG,
                to.name(),
                pauseReason == null ? null : pauseReason.name(),
                pausedFromState == null ? null : pausedFromState.name(),
                configMode == null ? null : configMode.name(),
                desiredConfig, desiredFingerprint,
                operationId,
                Timestamp.from(stateChangedAt),
                Timestamp.from(stateChangedAt),
                groupId, from.name(), expectedVersion);
    }

    /**
     * Locks the group row at the expected {@code state} with {@code SELECT ... FOR UPDATE} (§2.3,
     * the plan-driven exceptions) and returns its current {@code version}. Null when no row matches
     * (the state moved), so the caller treats it as a missed guard. Must run inside the caller's
     * transaction (MANDATORY); the lock is held to its commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long lockRow(UUID groupId, GroupState expectedState) {
        return jdbc.query(LOCK_ROW, rs -> rs.next() ? rs.getLong(1) : null, groupId, expectedState.name());
    }

    /** The group's latest transition {@code effective_at} (by {@code id}), or null when none exists. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Instant latestEffectiveAt(UUID groupId) {
        Timestamp ts = jdbc.query(LATEST_EFFECTIVE_AT, rs -> rs.next() ? rs.getTimestamp(1) : null, groupId);
        return ts == null ? null : ts.toInstant();
    }

    /**
     * Inserts the append-only {@code spot_group_transition} row (§2.4). {@code effectiveAt} must
     * already be clamped by the caller; {@code trg_sgt_monotonic} is the backstop (DR-01).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertTransition(UUID groupId, GroupState fromState, GroupState toState, String reasonCode,
                                 String actorType, String actorId, UUID operationId,
                                 Instant occurredAt, Instant effectiveAt) {
        jdbc.update(INSERT_TRANSITION,
                groupId,
                fromState == null ? null : fromState.name(),
                toState.name(),
                reasonCode,
                actorType,
                actorId,
                operationId,
                Timestamp.from(occurredAt),
                Timestamp.from(effectiveAt));
    }
}
