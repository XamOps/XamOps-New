package com.xammer.cloud.repository.autospotting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The native lease statements of design-core.md §3.3 and the fence check of §3.4. JdbcTemplate only:
 * every statement uses the database's {@code clock_timestamp()} for its time comparison, never the
 * JVM clock, so correctness doesn't depend on clock sync across instances. The {@code spot_group}
 * entity is {@code @Immutable}, so JPA never writes the lease columns — these guarded native UPDATEs
 * own them.
 *
 * <p>Each of acquire, renew and release runs in its own single-statement transaction
 * ({@code REQUIRES_NEW}), as the design requires. The fence check in {@link #fenceHeld} is called by
 * {@link com.xammer.cloud.service.autospotting.core.lease.FencedTx} as the first statement of the
 * caller's transaction and therefore joins it (no new transaction).
 */
@Repository
public class LeaseRepository {

    // ── group lease (spot_group), design-core.md §3.3 ─────────────────────────
    static final String GROUP_ACQUIRE =
            "UPDATE spot_group"
                    + "   SET lease_holder = ?, lease_expires_at = clock_timestamp() + (? * INTERVAL '1 second'),"
                    + "       lease_generation = lease_generation + 1"
                    + " WHERE id = ? AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp())"
                    + " RETURNING lease_generation";
    static final String GROUP_RENEW =
            "UPDATE spot_group SET lease_expires_at = clock_timestamp() + (? * INTERVAL '1 second')"
                    + " WHERE id = ? AND lease_holder = ? AND lease_generation = ?"
                    + "   AND lease_expires_at > clock_timestamp()";
    static final String GROUP_RELEASE =
            "UPDATE spot_group SET lease_expires_at = clock_timestamp()"
                    + " WHERE id = ? AND lease_holder = ? AND lease_generation = ?";
    static final String GROUP_FENCE =
            "SELECT 1 FROM spot_group"
                    + " WHERE id = ? AND lease_holder = ? AND lease_generation = ?"
                    + "   AND lease_expires_at > clock_timestamp() FOR UPDATE";

    // ── job lease (autospotting_job_lease), design-core.md §3.3, §3.5 ─────────
    static final String JOB_BOOTSTRAP =
            "INSERT INTO autospotting_job_lease (name, generation, expires_at)"
                    + " VALUES (?, 0, clock_timestamp()) ON CONFLICT (name) DO NOTHING";
    static final String JOB_ACQUIRE =
            "UPDATE autospotting_job_lease"
                    + "   SET holder = ?, acquired_at = clock_timestamp(),"
                    + "       expires_at = clock_timestamp() + (? * INTERVAL '1 second'),"
                    + "       generation = generation + 1"
                    + " WHERE name = ? AND (expires_at IS NULL OR expires_at <= clock_timestamp())"
                    + " RETURNING generation";
    static final String JOB_RENEW =
            "UPDATE autospotting_job_lease SET expires_at = clock_timestamp() + (? * INTERVAL '1 second')"
                    + " WHERE name = ? AND holder = ? AND generation = ?"
                    + "   AND expires_at > clock_timestamp()";
    // Release keeps the minimum hold: expires_at = GREATEST(clock_timestamp(), acquired_at + minHold)
    // (§3.5). last_run_key and last_completed_at are stamped at the same time.
    static final String JOB_RELEASE =
            "UPDATE autospotting_job_lease"
                    + "   SET expires_at = GREATEST(clock_timestamp(),"
                    + "                             acquired_at + (? * INTERVAL '1 second')),"
                    + "       last_run_key = COALESCE(?, last_run_key), last_completed_at = clock_timestamp()"
                    + " WHERE name = ? AND holder = ? AND generation = ?";
    static final String JOB_LAST_RUN_KEY =
            "SELECT last_run_key FROM autospotting_job_lease WHERE name = ?";

    private final JdbcTemplate jdbc;

    /** @param dataSource the {@code @Primary} application DataSource */
    public LeaseRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ── group lease ───────────────────────────────────────────────────────────

    /** Acquire (group). Returns the new generation, or null when the lease is held elsewhere. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long acquireGroup(UUID groupId, String token, Duration ttl) {
        return jdbc.query(GROUP_ACQUIRE,
                rs -> rs.next() ? rs.getLong(1) : null,
                token, ttl.toSeconds(), groupId);
    }

    /** Renew (group heartbeat). Returns true when one row was updated (still held by this token). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renewGroup(UUID groupId, String token, long generation, Duration ttl) {
        return jdbc.update(GROUP_RENEW, ttl.toSeconds(), groupId, token, generation) == 1;
    }

    /** Release (group). Expires the lease now so another instance can take it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseGroup(UUID groupId, String token, long generation) {
        jdbc.update(GROUP_RELEASE, groupId, token, generation);
    }

    /**
     * The fence check (§3.4), run as the first statement of the caller's already-open transaction.
     * Returns true when the lease is still held by {@code token}/{@code generation}. Joins the
     * current transaction ({@code MANDATORY}): it must never open its own.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean fenceHeld(UUID groupId, String token, long generation) {
        return !jdbc.queryForList(GROUP_FENCE, Integer.class, groupId, token, generation).isEmpty();
    }

    // ── job lease ──────────────────────────────────────────────────────────────

    /** Bootstrap the row for {@code name} if absent (INSERT ... ON CONFLICT DO NOTHING). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureJobRow(String name) {
        jdbc.update(JOB_BOOTSTRAP, name);
    }

    /** Acquire (job). Returns the new generation, or null when held elsewhere or inside its hold. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long acquireJob(String name, String token, Duration ttl) {
        return jdbc.query(JOB_ACQUIRE,
                rs -> rs.next() ? rs.getLong(1) : null,
                token, ttl.toSeconds(), name);
    }

    /** Renew (job heartbeat). Returns true when still held by this token. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renewJob(String name, String token, long generation, Duration ttl) {
        return jdbc.update(JOB_RENEW, ttl.toSeconds(), name, token, generation) == 1;
    }

    /**
     * Release (job), applying the minimum hold of §3.5 and stamping {@code last_run_key} (null keeps
     * the stored value). Returns true when this token still held the lease.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean releaseJob(String name, String token, long generation, Duration minHold, String runKey) {
        return jdbc.update(JOB_RELEASE, minHold.toSeconds(), runKey, name, token, generation) == 1;
    }

    /** The job's recorded {@code last_run_key}, or null. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public String lastRunKey(String name) {
        return jdbc.query(JOB_LAST_RUN_KEY, rs -> rs.next() ? rs.getString(1) : null, name);
    }

    // ── test/diagnostic helpers (no behavior change) ───────────────────────────

    /** The lease's current expiry, read with the DB clock for tests. Null when the group is absent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Instant groupLeaseExpiresAt(UUID groupId) {
        Timestamp ts = jdbc.query("SELECT lease_expires_at FROM spot_group WHERE id = ?",
                rs -> rs.next() ? rs.getTimestamp(1) : null, groupId);
        return ts == null ? null : ts.toInstant();
    }
}
