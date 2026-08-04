package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindSyncLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface UpwindSyncLockRepository extends JpaRepository<UpwindSyncLock, String> {

    /**
     * Creates the lease row if absent, already expired so the first caller can take it.
     * ON CONFLICT DO NOTHING makes concurrent first-boots safe.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO upwind_sync_lock (name, expires_at) VALUES (:name, :epoch) "
            + "ON CONFLICT (name) DO NOTHING", nativeQuery = true)
    int ensureRow(@Param("name") String name, @Param("epoch") Instant epoch);

    /**
     * Claims the lease only if it is free or expired. The WHERE clause makes this atomic:
     * concurrent instances issue the same UPDATE and exactly one reports a changed row.
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE upwind_sync_lock SET holder = :holder, acquired_at = :now, "
            + "expires_at = :expiresAt WHERE name = :name AND expires_at <= :now",
            nativeQuery = true)
    int tryAcquire(@Param("name") String name, @Param("holder") String holder,
                   @Param("now") Instant now, @Param("expiresAt") Instant expiresAt);

    /** Frees the lease, but only if we still hold it — never steals another holder's lease. */
    @Transactional
    @Modifying
    @Query(value = "UPDATE upwind_sync_lock SET expires_at = :now "
            + "WHERE name = :name AND holder = :holder", nativeQuery = true)
    int release(@Param("name") String name, @Param("holder") String holder,
                @Param("now") Instant now);
}
