package com.xammer.cloud.repository;

import com.xammer.cloud.domain.MattersAccessAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Read and retention access to the DSPM audit trail.
 *
 * Deliberately has no update method. The trail is append-only: an audit log that can be edited
 * answers a weaker question than one that cannot, and the only legitimate removal is age-based
 * expiry ({@link #deleteOlderThan}).
 */
@Repository
public interface MattersAccessAuditRepository extends JpaRepository<MattersAccessAudit, Long> {

    /** One client's trail, newest first — the per-customer view in the admin page. */
    Page<MattersAccessAudit> findByClientIdOrderByCreatedAtDesc(Long clientId, Pageable pageable);

    /** Everything, newest first. */
    Page<MattersAccessAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Filtered view, newest first. Every parameter is optional.
     *
     * The {@code cast(:x as text) IS NULL OR} idiom rather than {@code :x::text} — Hibernate's native
     * query parser misreads the {@code ::} cast, a trap already hit by the Upwind repositories.
     */
    @Query(value = """
            SELECT * FROM matters_access_audit
            WHERE (cast(:clientId as bigint) IS NULL OR client_id = :clientId)
              AND (cast(:action   as text)   IS NULL OR action    = :action)
              AND (cast(:outcome  as text)   IS NULL OR outcome   = :outcome)
              AND (cast(:since    as timestamptz) IS NULL OR created_at >= :since)
            ORDER BY created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM matters_access_audit
            WHERE (cast(:clientId as bigint) IS NULL OR client_id = :clientId)
              AND (cast(:action   as text)   IS NULL OR action    = :action)
              AND (cast(:outcome  as text)   IS NULL OR outcome   = :outcome)
              AND (cast(:since    as timestamptz) IS NULL OR created_at >= :since)
            """,
            nativeQuery = true)
    Page<MattersAccessAudit> search(@Param("clientId") Long clientId,
                                    @Param("action") String action,
                                    @Param("outcome") String outcome,
                                    @Param("since") Instant since,
                                    Pageable pageable);

    /**
     * Retention sweep for the high-volume credential-use rows only.
     *
     * Operator mutations — who changed a key, who repointed a project, who granted the add-on — are
     * never deleted by age. They are low-volume and are the rows with lasting value. Reads are
     * different: one page view produces several, so without expiry this table grows without bound
     * for no proportionate benefit.
     *
     * Batched, so one statement cannot hold a long lock on a large delete. Repeat until it reports 0.
     */
    @Modifying
    @Query(value = """
            DELETE FROM matters_access_audit
            WHERE id IN (
                SELECT id FROM matters_access_audit
                WHERE action = 'CREDENTIAL_USED' AND created_at < :cutoff
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
