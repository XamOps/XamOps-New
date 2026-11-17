package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.AutoSpottingAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Finders for {@link AutoSpottingAudit} (plan-phase-03.md task 3.2, design-core.md §8). Append-only:
 * the database trigger rejects UPDATE and DELETE, and this interface exposes only {@code save}
 * (inherited) and finders. The paginated query API arrives with the operator reads.
 */
@Repository
public interface AutoSpottingAuditRepository extends JpaRepository<AutoSpottingAudit, Long> {

    Page<AutoSpottingAudit> findByGroupIdOrderByOccurredAtDesc(UUID groupId, Pageable pageable);

    Page<AutoSpottingAudit> findByCloudAccountIdOrderByOccurredAtDesc(Long cloudAccountId, Pageable pageable);

    /** The account's audit rows in {@code [from, to)}, newest first (plan-phase-14.md task 14.2). */
    Page<AutoSpottingAudit> findByCloudAccountIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
            Long cloudAccountId, Instant from, Instant to, Pageable pageable);

    /** The same, for one group of the account. */
    Page<AutoSpottingAudit> findByCloudAccountIdAndGroupIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
            Long cloudAccountId, UUID groupId, Instant from, Instant to, Pageable pageable);
}
