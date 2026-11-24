package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists and reads the original-configuration snapshot of a GCP conversion (design-gcp.md §2.6
 * step 2, §5; design-core.md §2.4). Rows in {@code spot_group_snapshot} are immutable (a trigger
 * rejects UPDATE and DELETE) and keyed by the operation that captured them ({@code captured_by_op}),
 * so a re-run after a crash finds the row instead of writing another. The insert and the
 * {@code spot_group.snapshot_id} link are one fenced transaction on the group lease.
 */
@Component
public class GcpSnapshotStore {

    /** A stored snapshot. */
    public record Stored(UUID id, UUID groupId, boolean exact, String payload, String payloadSha256) {
    }

    private final JdbcTemplate jdbc;
    private final FencedTx fencedTx;

    public GcpSnapshotStore(JdbcTemplate jdbc, FencedTx fencedTx) {
        this.jdbc = jdbc;
        this.fencedTx = fencedTx;
    }

    /** The snapshot this operation captured, if it already ran. */
    public Optional<Stored> findByOp(UUID operationId) {
        return one("SELECT id, group_id, exact, payload::text, payload_sha256 FROM spot_group_snapshot"
                + " WHERE captured_by_op = ? ORDER BY captured_at LIMIT 1", operationId);
    }

    /** The snapshot the group row currently points at (an ADOPT's reconstructed snapshot). */
    public Optional<Stored> findLinked(UUID groupId) {
        return one("SELECT s.id, s.group_id, s.exact, s.payload::text, s.payload_sha256 FROM spot_group_snapshot s"
                + " JOIN spot_group g ON g.snapshot_id = s.id WHERE g.id = ?", groupId);
    }

    /** The snapshot linked to the group with this key on this account (a plan knows the key, not the row id). */
    public Optional<Stored> findLinkedByKey(Long cloudAccountId, String groupKey) {
        return one("SELECT s.id, s.group_id, s.exact, s.payload::text, s.payload_sha256 FROM spot_group_snapshot s"
                + " JOIN spot_group g ON g.snapshot_id = s.id WHERE g.cloud_account_id = ? AND g.group_key = ?"
                + " AND g.provider = 'GCP'", cloudAccountId, groupKey);
    }
    /**
     * Persist {@code payload} for {@code operationId} and link it to the group, in one fenced
     * transaction. Idempotent: a second call for the same operation returns the first row.
     */
    public Stored persist(LeaseHandle handle, UUID operationId, UUID groupId, SnapshotPayload payload, Instant now) {
        Optional<Stored> existing = findByOp(operationId);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        fencedTx.run(handle, () -> {
            jdbc.update("INSERT INTO spot_group_snapshot (id, group_id, provider, schema_version, exact, payload,"
                            + " payload_sha256, captured_at, captured_by_op) VALUES (?, ?, 'GCP', ?, ?, ?::jsonb, ?, ?, ?)",
                    id, groupId, payload.schemaVersion(), payload.exact(), payload.payloadJson(),
                    payload.payloadSha256(), Timestamp.from(now), operationId);
            jdbc.update("UPDATE spot_group SET snapshot_id = ?, updated_at = ? WHERE id = ?",
                    id, Timestamp.from(now), groupId);
        });
        return new Stored(id, groupId, payload.exact(), payload.payloadJson(), payload.payloadSha256());
    }

    private Optional<Stored> one(String sql, Object... arg) {
        List<Stored> rows = jdbc.query(sql, (rs, i) -> new Stored((UUID) rs.getObject(1), (UUID) rs.getObject(2),
                rs.getBoolean(3), rs.getString(4), rs.getString(5)), arg);
        return rows.stream().findFirst();
    }
}
