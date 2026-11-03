package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.DriftStatus;
import com.xammer.cloud.domain.autospotting.Provider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The reconciler's runtime-column writes and the DR-23 sweep-order finder (design-core.md §5.1,
 * §3.4 "Unfenced by design"; plan-phase-08.md task 8.1). Runtime columns ({@code last_reconciled_at},
 * {@code last_error_code}/{@code last_error_message}, {@code health_state}, {@code last_capacity},
 * {@code drift_status}/{@code drift_detail}, {@code reconcile_hint_at}) are written without the lease
 * fence and never touch {@code version} (§3.4): they do not change cloud-facing progress, and a
 * reconciler write to them must never fail a concurrent runner's lifecycle transition. The lifecycle
 * state writes the reconciler makes (PAUSE/UNPAUSE/DEGRADED/GONE) go through
 * {@code GroupLifecycleService} under the group lease, not here.
 *
 * <p>JdbcTemplate only, like {@link SpotGroupLifecycleRepository}, because {@code spot_group} is
 * {@code @Immutable} and JPA never writes its columns.
 */
@Repository
public class ReconcilerStore {

    // DR-23 sweep order (§5.1): non-terminal groups for a provider, due hints first (reconcile_hint_at
    // set), then managed states by oldest last_reconciled_at, then the rest by oldest
    // last_reconciled_at. NULLS FIRST so a never-reconciled group is taken before an old one. The cap
    // is applied by the caller (LIMIT bound at call time). PLANNED is included (non-terminal).
    static final String SWEEP_ORDER =
            "SELECT id FROM spot_group"
                    + " WHERE provider = ?"
                    + "   AND state IN ('PLANNED','APPLYING','ACTIVE','DEGRADED','FALLBACK','PAUSED','REVERTING')"
                    + " ORDER BY"
                    + "   (reconcile_hint_at IS NOT NULL) DESC,"
                    + "   CASE WHEN state IN ('ACTIVE','DEGRADED','FALLBACK','PAUSED','APPLYING','REVERTING')"
                    + "        THEN 0 ELSE 1 END ASC,"
                    + "   last_reconciled_at ASC NULLS FIRST,"
                    + "   id ASC"
                    + " LIMIT ?";

    // The max staleness over managed groups for the overflow gauge (§5.1): the greatest
    // now - last_reconciled_at among managed groups for a provider. Null when none exists.
    static final String MAX_STALENESS_SECONDS =
            "SELECT EXTRACT(EPOCH FROM (now() - MIN(last_reconciled_at)))"
                    + " FROM spot_group"
                    + " WHERE provider = ?"
                    + "   AND state IN ('ACTIVE','DEGRADED','FALLBACK','PAUSED','APPLYING','REVERTING')"
                    + "   AND last_reconciled_at IS NOT NULL";

    // Runtime write: stamp last_reconciled_at and the health/capacity JSON, clear the reconcile hint.
    // Unfenced, never touches version (§3.4).
    static final String WRITE_RUNTIME =
            "UPDATE spot_group"
                    + "   SET last_reconciled_at = ?, health_state = ?::jsonb, last_capacity = ?::jsonb,"
                    + "       reconcile_hint_at = NULL, updated_at = ?"
                    + " WHERE id = ?";

    // Runtime write: the drift classification result (drift_status + drift_detail). Unfenced (§3.4).
    static final String WRITE_DRIFT =
            "UPDATE spot_group SET drift_status = ?, drift_detail = ?::jsonb, updated_at = ? WHERE id = ?";

    // Runtime write: last_error_code / last_error_message (PERMISSION, NOT_FOUND_ONCE, cleared on
    // recovery). Unfenced (§3.4).
    static final String WRITE_ERROR =
            "UPDATE spot_group SET last_error_code = ?, last_error_message = ?, updated_at = ? WHERE id = ?";

    private final JdbcTemplate jdbc;

    public ReconcilerStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** The DR-23-ordered ids of the groups to sweep for {@code provider}, capped at {@code cap} (§5.1). */
    public List<UUID> sweepOrder(Provider provider, int cap) {
        return jdbc.query(SWEEP_ORDER, (rs, n) -> (UUID) rs.getObject(1), provider.name(), cap);
    }

    /** The max staleness in seconds over the provider's managed groups, or null when none (§5.1 gauge). */
    public Double maxStalenessSeconds(Provider provider) {
        return jdbc.query(MAX_STALENESS_SECONDS, rs -> {
            if (rs.next()) {
                double v = rs.getDouble(1);
                return rs.wasNull() ? null : v;
            }
            return null;
        }, provider.name());
    }

    /** Stamp {@code last_reconciled_at} with the latest health/capacity JSON (runtime columns, unfenced). */
    public void writeRuntime(UUID groupId, String healthJson, String capacityJson, Instant now) {
        jdbc.update(WRITE_RUNTIME, Timestamp.from(now), healthJson, capacityJson, Timestamp.from(now), groupId);
    }

    /** Write the drift classification ({@code drift_status}, {@code drift_detail}) (runtime, unfenced). */
    public void writeDrift(UUID groupId, DriftStatus driftStatus, String driftDetailJson, Instant now) {
        jdbc.update(WRITE_DRIFT, driftStatus.name(), driftDetailJson, Timestamp.from(now), groupId);
    }

    /** Write {@code last_error_code}/{@code last_error_message} (runtime, unfenced). Nulls clear them. */
    public void writeError(UUID groupId, String errorCode, String errorMessage, Instant now) {
        jdbc.update(WRITE_ERROR, errorCode, errorMessage, Timestamp.from(now), groupId);
    }
}
