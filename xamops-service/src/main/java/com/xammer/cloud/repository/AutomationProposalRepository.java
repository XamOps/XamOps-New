package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AutomationProposal;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Proposal storage. <strong>No method looks a proposal up by id alone</strong> (design 3.8, structural
 * IDOR rule): the only lookups take the cluster id, and every write is a single guarded
 * {@code UPDATE ... WHERE id = ? AND cluster_config_id = ? AND status = ?}, so a proposal that belongs
 * to another cluster is indistinguishable from a missing one. A return value of 0 from a writer means
 * the caller lost the race (or the scope did not match) and nothing changed.
 *
 * <p>{@code version} is bumped by the writers on status transitions only. Writers clear the
 * persistence context, so callers re-read after a transition instead of trusting a stale entity.
 */
@Repository
public interface AutomationProposalRepository extends JpaRepository<AutomationProposal, String> {

    String LIVE = "(p.status in ('PENDING','APPROVED','DISPATCHED') "
            + "or (p.status = 'REFUSED' and p.refusalStage = 'PROPOSAL'))";

    // ------------------------------------------------------------------ reads (always cluster-scoped)

    Optional<AutomationProposal> findByIdAndClusterConfigId(String id, Long clusterConfigId);

    /** Live rows for a dedupe key: PENDING, APPROVED, DISPATCHED and proposal-stage REFUSED. */
    @Query("select p from AutomationProposal p where p.clusterConfigId = :cid and p.dedupeKey = :key and " + LIVE)
    List<AutomationProposal> findLive(@Param("cid") Long clusterConfigId, @Param("key") String dedupeKey);

    /** When the most recent cooldown-bearing terminal row for this key finished (null if none). */
    @Query("select max(p.finishedAt) from AutomationProposal p where p.clusterConfigId = :cid "
            + "and p.dedupeKey = :key and (p.status in ('REJECTED','FAILED','EXECUTED') "
            + "or (p.status = 'REFUSED' and p.refusalStage = 'EXECUTION'))")
    Instant lastCooldownTerminalAt(@Param("cid") Long clusterConfigId, @Param("key") String dedupeKey);

    long countByClusterConfigIdAndStatus(Long clusterConfigId, String status);

    /** Whether any node proposal of this cluster is DISPATCHED (the one-in-flight rule). */
    @Query("select count(p) from AutomationProposal p where p.clusterConfigId = :cid and p.status = 'DISPATCHED' "
            + "and p.targetKind = 'NODE'")
    long countDispatchedNodeActions(@Param("cid") Long clusterConfigId);

    /** Newest first; the cursor is {@code (proposedAt, id) < (ts, id)}. */
    @Query("select p from AutomationProposal p where p.clusterConfigId = :cid and p.status in :statuses "
            + "and (p.proposedAt < :ts or (p.proposedAt = :ts and p.id < :lastId)) "
            + "order by p.proposedAt desc, p.id desc")
    List<AutomationProposal> page(@Param("cid") Long clusterConfigId,
                                  @Param("statuses") Collection<String> statuses,
                                  @Param("ts") Instant proposedBefore,
                                  @Param("lastId") String lastId,
                                  Pageable pageable);

    @Query("select p.status, count(p) from AutomationProposal p where p.clusterConfigId = :cid group by p.status")
    List<Object[]> countsByStatus(@Param("cid") Long clusterConfigId);

    /** The oldest APPROVED node proposal of this cluster whose claim window is still open. */
    @Query("select p from AutomationProposal p where p.clusterConfigId = :cid and p.status = 'APPROVED' "
            + "and p.targetKind = 'NODE' and p.approvedUntil > :now order by p.decidedAt asc, p.id asc")
    List<AutomationProposal> findClaimable(@Param("cid") Long clusterConfigId, @Param("now") Instant now,
                                           Pageable pageable);

    @Query("select p from AutomationProposal p where p.clusterConfigId = :cid and p.status = 'DISPATCHED' "
            + "and p.targetKind = 'NODE' order by p.dispatchedAt asc, p.id asc")
    List<AutomationProposal> findDispatchedNodeActions(@Param("cid") Long clusterConfigId);

    // ------------------------------------------------------------------ sweeper reads (all clusters)

    @Query("select p from AutomationProposal p where p.status = 'PENDING' and p.expiresAt < :now")
    List<AutomationProposal> findExpiredPending(@Param("now") Instant now);

    @Query("select p from AutomationProposal p where p.status = 'APPROVED' and p.approvedUntil < :now")
    List<AutomationProposal> findExpiredApproved(@Param("now") Instant now);

    @Query("select p from AutomationProposal p where p.status = 'DISPATCHED'")
    List<AutomationProposal> findAllDispatched();

    /** PENDING and proposal-stage REFUSED rows whose source has stopped re-sending them. */
    @Query("select p from AutomationProposal p where p.source = :source and "
            + "(p.status = 'PENDING' or (p.status = 'REFUSED' and p.refusalStage = 'PROPOSAL')) "
            + "and p.lastSeenAt < :cutoff")
    List<AutomationProposal> findNotReseen(@Param("source") String source, @Param("cutoff") Instant cutoff);

    @Query("select p.id from AutomationProposal p where p.clusterConfigId = :cid and p.status in ('PENDING','APPROVED')")
    List<String> findOpenIds(@Param("cid") Long clusterConfigId);

    // ------------------------------------------------------------------ guarded writers

    /** Same fingerprint re-seen while PENDING: refresh last-seen and evidence, never version or fingerprint. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.lastSeenAt = :now, p.evidenceJson = :evidence "
            + "where p.id = :id and p.clusterConfigId = :cid and p.status = 'PENDING' "
            + "and p.planFingerprint = :fingerprint")
    int touchPending(@Param("id") String id, @Param("cid") Long clusterConfigId,
                     @Param("fingerprint") String fingerprint, @Param("now") Instant now,
                     @Param("evidence") String evidence);

    /** A live APPROVED/DISPATCHED/proposal-REFUSED row re-seen: only last-seen moves (evidence is frozen). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.lastSeenAt = :now where p.id = :id and p.clusterConfigId = :cid "
            + "and " + LIVE)
    int touchSeen(@Param("id") String id, @Param("cid") Long clusterConfigId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.status = 'STALE', p.statusReason = :reason, p.version = p.version + 1, "
            + "p.finishedAt = :now where p.id = :id and p.clusterConfigId = :cid and p.status = :from")
    int markStale(@Param("id") String id, @Param("cid") Long clusterConfigId, @Param("from") String from,
                  @Param("reason") String reason, @Param("now") Instant now);

    /** Proposal-stage REFUSED row cleared or no longer observed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.status = 'STALE', p.statusReason = :reason, p.version = p.version + 1, "
            + "p.finishedAt = :now where p.id = :id and p.clusterConfigId = :cid and p.status = 'REFUSED' "
            + "and p.refusalStage = 'PROPOSAL'")
    int markRefusedStale(@Param("id") String id, @Param("cid") Long clusterConfigId,
                         @Param("reason") String reason, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.status = 'EXPIRED', p.statusReason = :reason, "
            + "p.version = p.version + 1, p.finishedAt = :now "
            + "where p.id = :id and p.clusterConfigId = :cid and p.status = :from")
    int markExpired(@Param("id") String id, @Param("cid") Long clusterConfigId, @Param("from") String from,
                    @Param("reason") String reason, @Param("now") Instant now);

    /** PENDING to APPROVED, bound to the version and fingerprint the approver saw. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.status = 'APPROVED', p.version = p.version + 1, "
            + "p.decidedBy = :by, p.decidedByName = :byName, p.decidedAt = :now, p.decisionNote = :note, "
            + "p.approvedUntil = :approvedUntil "
            + "where p.id = :id and p.clusterConfigId = :cid and p.status = 'PENDING' "
            + "and p.version = :version and p.planFingerprint = :fingerprint")
    int approve(@Param("id") String id, @Param("cid") Long clusterConfigId, @Param("version") long version,
                @Param("fingerprint") String fingerprint, @Param("by") Long by,
                @Param("byName") String byName, @Param("note") String note, @Param("now") Instant now,
                @Param("approvedUntil") Instant approvedUntil);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.status = 'REJECTED', p.version = p.version + 1, "
            + "p.decidedBy = :by, p.decidedByName = :byName, p.decidedAt = :now, p.decisionNote = :note, "
            + "p.finishedAt = :now "
            + "where p.id = :id and p.clusterConfigId = :cid and p.status = 'PENDING' and p.version = :version")
    int reject(@Param("id") String id, @Param("cid") Long clusterConfigId, @Param("version") long version,
               @Param("by") Long by, @Param("byName") String byName, @Param("note") String note,
               @Param("now") Instant now);

    /** APPROVED to DISPATCHED (agent claim, or the Java executor starting). Only inside the claim window. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.status = 'DISPATCHED', p.dispatchedAt = :now, "
            + "p.dispatchAttempts = p.dispatchAttempts + 1, p.lastProgressAt = null, p.version = p.version + 1 "
            + "where p.id = :id and p.clusterConfigId = :cid and p.status = 'APPROVED' and p.approvedUntil > :now")
    int dispatch(@Param("id") String id, @Param("cid") Long clusterConfigId, @Param("now") Instant now);

    /** DISPATCHED back to APPROVED because the Java executor pool rejected the work (nothing ran). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.status = 'APPROVED', p.dispatchAttempts = p.dispatchAttempts - 1, "
            + "p.version = p.version + 1 where p.id = :id and p.clusterConfigId = :cid and p.status = 'DISPATCHED' "
            + "and p.dispatchAttempts > 0")
    int undoDispatch(@Param("id") String id, @Param("cid") Long clusterConfigId);

    /** The agent's {@code not-started} outcome: re-queue once, only while the approval window is open. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.status = 'APPROVED', p.statusReason = 'NOT_STARTED', "
            + "p.version = p.version + 1 where p.id = :id and p.clusterConfigId = :cid and p.status = 'DISPATCHED' "
            + "and p.dispatchAttempts < 2 and p.approvedUntil > :now")
    int requeueNotStarted(@Param("id") String id, @Param("cid") Long clusterConfigId, @Param("now") Instant now);

    /** DISPATCHED to a terminal status with the outcome recorded. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.status = :to, p.statusReason = :reason, p.refusalStage = :stage, "
            + "p.outcomeStatus = :outcomeStatus, p.outcomeVerified = :verified, p.outcomeJson = :outcomeJson, "
            + "p.correlationId = :correlationId, p.finishedAt = :now, p.version = p.version + 1 "
            + "where p.id = :id and p.clusterConfigId = :cid and p.status = 'DISPATCHED'")
    int finish(@Param("id") String id, @Param("cid") Long clusterConfigId, @Param("to") String to,
               @Param("reason") String reason, @Param("stage") String refusalStage,
               @Param("outcomeStatus") String outcomeStatus, @Param("verified") Boolean verified,
               @Param("outcomeJson") String outcomeJson, @Param("correlationId") String correlationId,
               @Param("now") Instant now);

    /** DISPATCHED past its lease with no agent progress. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.status = 'FAILED', p.statusReason = :reason, "
            + "p.outcomeStatus = 'failed', p.finishedAt = :now, p.version = p.version + 1 "
            + "where p.id = :id and p.clusterConfigId = :cid and p.status = 'DISPATCHED'")
    int failDispatched(@Param("id") String id, @Param("cid") Long clusterConfigId,
                       @Param("reason") String reason, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AutomationProposal p set p.lastProgressAt = :now "
            + "where p.id = :id and p.clusterConfigId = :cid and p.status = 'DISPATCHED'")
    int recordProgress(@Param("id") String id, @Param("cid") Long clusterConfigId, @Param("now") Instant now);

    /** Retention: terminal rows older than the cutoff. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AutomationProposal p where p.finishedAt is not null and p.finishedAt < :cutoff")
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff);
}
