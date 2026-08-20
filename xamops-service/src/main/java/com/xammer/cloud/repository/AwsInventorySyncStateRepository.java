package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AwsInventorySyncState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Sync state, keyed by {@code cloudAccountId} — which is the entity's primary key, so
 * {@code findById} is the only lookup callers need.
 *
 * <p>
 * There is deliberately no {@code findByAwsAccountId} here. It existed and returned
 * {@link java.util.Optional}, which quietly assumed one sync state per AWS account. That
 * assumption breaks the moment a customer disconnects and reconnects: the second
 * connection creates a new {@code cloud_account} row, so the same 12-digit AWS account id
 * now appears on two sync-state rows and the lookup throws
 * {@code NonUniqueResultException}. The read path caught it, logged a warning and fell
 * back to describes — so the entire inventory plane silently switched itself off while
 * looking healthy from the outside. Restoring the method, in any form, restores that
 * failure mode; resolve the {@code cloud_account} row first and use {@link #findById}.
 */
@Repository
public interface AwsInventorySyncStateRepository extends JpaRepository<AwsInventorySyncState, Long> {

    /** See {@code AwsCloudResourceRepository.deleteOrphanedByMissingAccount}. */
    @Modifying
    @Query("DELETE FROM AwsInventorySyncState s WHERE s.cloudAccountId NOT IN "
            + "(SELECT a.id FROM CloudAccount a)")
    int deleteOrphanedByMissingAccount();
}
