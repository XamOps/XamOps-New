package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.GroupState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The native write statements the discovery engine uses (design-core.md §2.5, plan-phase-08.md task
 * 8.3). {@code spot_group} is {@code @Immutable}, so JPA never writes its lifecycle columns; these
 * guarded native statements own the discovery-driven inserts and the two discovery-driven state
 * moves (LEGACY_UNCONFIRMED → GONE on an identity change, LEGACY_UNCONFIRMED → UNMANAGED after two
 * runs without a marker or Spot share).
 *
 * <p>Discovery never mutates the cloud and never runs a conversion. It only inserts a
 * LEGACY_UNCONFIRMED row with a reconstructed snapshot ({@code exact=false}, written to
 * {@code spot_group_snapshot} by the caller via the snapshot repository), and moves existing
 * discovery rows between non-billable states. No fence is needed: a LEGACY_UNCONFIRMED group has no
 * op and no lease holder, and the discovery job holds the {@code discovery:{cloud}} job lease for
 * the whole run (§3.2). Each write runs {@link Propagation#MANDATORY} inside the caller's
 * transaction so the matching {@code spot_group_transition} and audit rows commit with it.
 */
@Repository
public class DiscoveryStore {

    // A discovery-inserted LEGACY_UNCONFIRMED row. config_mode MANAGED, drift NONE, version 0; the
    // reconstructed snapshot id (exact=false) is linked when present. owned_artifacts starts empty.
    static final String INSERT_LEGACY_UNCONFIRMED =
            "INSERT INTO spot_group"
                    + " (id, provider, cloud_account_id, client_id, group_key, provider_stable_id,"
                    + "  state, drift_status, config_mode, desired_config, desired_fingerprint,"
                    + "  snapshot_id, owned_artifacts, last_error_code, lease_generation, version,"
                    + "  state_changed_at, last_reconciled_at, created_by, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 'LEGACY_UNCONFIRMED', 'NONE', 'MANAGED', ?::jsonb, ?,"
                    + "  ?, '{}'::jsonb, NULL, 0, 0, ?, ?, 'discovery', ?, ?)"
                    + " ON CONFLICT DO NOTHING";

    // Identity change (§2.5 rule 1): a discovery row whose stable id no longer matches goes GONE
    // first, so a fresh row can be inserted under rules 2-3 or the next plan. Guarded on the row
    // being a discovery/bookkeeping row (LEGACY_UNCONFIRMED), so it never touches a v2-managed group.
    static final String MOVE_TO_GONE =
            "UPDATE spot_group"
                    + "   SET state = 'GONE', state_changed_at = ?, updated_at = ?, version = version + 1"
                    + " WHERE id = ? AND state = 'LEGACY_UNCONFIRMED'";

    // Two consecutive runs without a marker or Spot share (§2.5): LEGACY_UNCONFIRMED → UNMANAGED.
    static final String MOVE_TO_UNMANAGED =
            "UPDATE spot_group"
                    + "   SET state = 'UNMANAGED', state_changed_at = ?, updated_at = ?, version = version + 1"
                    + " WHERE id = ? AND state = 'LEGACY_UNCONFIRMED'";

    // Stamp last_reconciled_at on a discovery row this run (runtime column; unfenced, §3.4).
    static final String TOUCH_RECONCILED =
            "UPDATE spot_group SET last_reconciled_at = ?, updated_at = ? WHERE id = ?";

    // Mark the first marker-less discovery run on a row by stamping reconcile_hint_at (runtime
    // column; the two-run UNMANAGED rule reads it back on the next run). Unfenced (§3.4).
    static final String MARK_MARKERLESS_RUN =
            "UPDATE spot_group SET reconcile_hint_at = ?, last_reconciled_at = ?, updated_at = ?"
                    + " WHERE id = ? AND state = 'LEGACY_UNCONFIRMED'";

    // Clear the marker-less run marker when the group shows a marker or a Spot share again, so the
    // two-run rule needs two fresh consecutive marker-less runs (§2.5).
    static final String CLEAR_MARKERLESS_RUN =
            "UPDATE spot_group SET reconcile_hint_at = NULL, last_reconciled_at = ?, updated_at = ?"
                    + " WHERE id = ? AND state = 'LEGACY_UNCONFIRMED'";

    // Live LEGACY_UNCONFIRMED rows for an account, with their current stable id and the marker-less
    // run marker (reconcile_hint_at) for the two-run UNMANAGED check.
    static final String FIND_LEGACY_ROWS =
            "SELECT id, group_key, provider_stable_id, reconcile_hint_at FROM spot_group"
                    + " WHERE cloud_account_id = ? AND state = 'LEGACY_UNCONFIRMED'";

    private final JdbcTemplate jdbc;

    public DiscoveryStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Insert a LEGACY_UNCONFIRMED discovery row. Returns rows inserted (0 if the row already exists). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int insertLegacyUnconfirmed(UUID id, String provider, Long cloudAccountId, Long clientId,
                                       String groupKey, String providerStableId, String desiredConfigJson,
                                       String desiredFingerprint, UUID snapshotId, Instant now) {
        return jdbc.update(INSERT_LEGACY_UNCONFIRMED,
                id, provider, cloudAccountId, clientId, groupKey, providerStableId,
                desiredConfigJson, desiredFingerprint, snapshotId,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    /** Move a discovery row to GONE on an identity change (§2.5 rule 1). Returns rows updated. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int moveToGone(UUID id, Instant now) {
        return jdbc.update(MOVE_TO_GONE, Timestamp.from(now), Timestamp.from(now), id);
    }

    /** Move a discovery row to UNMANAGED after two runs without a marker or Spot share. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int moveToUnmanaged(UUID id, Instant now) {
        return jdbc.update(MOVE_TO_UNMANAGED, Timestamp.from(now), Timestamp.from(now), id);
    }

    /** Stamp {@code last_reconciled_at} on a discovery row (runtime column, unfenced). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void touchReconciled(UUID id, Instant now) {
        jdbc.update(TOUCH_RECONCILED, Timestamp.from(now), Timestamp.from(now), id);
    }

    /** Record this row's first marker-less discovery run (stamps {@code reconcile_hint_at}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markMarkerlessRun(UUID id, Instant now) {
        jdbc.update(MARK_MARKERLESS_RUN, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), id);
    }

    /** Clear the marker-less run marker when a marker or Spot share reappears. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearMarkerlessRun(UUID id, Instant now) {
        jdbc.update(CLEAR_MARKERLESS_RUN, Timestamp.from(now), Timestamp.from(now), id);
    }

    /** The live LEGACY_UNCONFIRMED rows for an account: id, group_key, stable id, marker-less marker. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<LegacyRow> findLegacyRows(Long cloudAccountId) {
        return jdbc.query(FIND_LEGACY_ROWS, (rs, n) -> {
            Timestamp hint = rs.getTimestamp("reconcile_hint_at");
            return new LegacyRow(
                    (UUID) rs.getObject("id"), rs.getString("group_key"), rs.getString("provider_stable_id"),
                    hint != null);
        }, cloudAccountId);
    }

    /**
     * A live discovery row: its id, group key, current provider-stable id, and whether a prior
     * marker-less discovery run was already recorded ({@code reconcile_hint_at} set).
     */
    public record LegacyRow(UUID id, String groupKey, String providerStableId, boolean markerlessRunRecorded) {

        public GroupState state() {
            return GroupState.LEGACY_UNCONFIRMED;
        }
    }
}
