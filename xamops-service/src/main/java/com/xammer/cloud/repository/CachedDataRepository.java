package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CachedData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface CachedDataRepository extends JpaRepository<CachedData, String> {

    /**
     * Deletes all snapshot rows whose cacheKey starts with the given prefix.
     * Used to invalidate section-level GCP cost snapshots when configuration
     * (e.g. the billing export table) changes for a project.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM CachedData c WHERE c.cacheKey LIKE CONCAT(:prefix, '%')")
    int deleteByCacheKeyPrefix(@Param("prefix") String prefix);

    /**
     * The keys among {@code keys} that have a row.
     *
     * <p>Selects the key only, so no payload is read and no legacy large-object reference is
     * resolved (NFR-4). Do not call it with an empty collection.
     */
    @Query("select c.cacheKey from CachedData c where c.cacheKey in :keys")
    List<String> findExistingKeys(@Param("keys") Collection<String> keys);

    /**
     * The stored text of {@code json_data}, not decoded: a legacy large-object reference comes back
     * as its OID text. Only convert-on-read uses it (design D13).
     */
    @Query(value = "SELECT json_data FROM cached_data WHERE cache_key = :key", nativeQuery = true)
    String findRawJsonData(@Param("key") String key);

    /**
     * Replaces a legacy large-object reference with its decoded JSON, only while the row still
     * holds that reference (design D13).
     *
     * <p>{@code last_updated} is not touched, and a concurrent rewrite makes the statement a no-op.
     * It runs in its own transaction, so a failure cannot poison the caller's. The parameters bind
     * as plain strings, so no large object is created, and the old object is not removed.
     *
     * @return 1 when the row was converted, 0 when it no longer holds {@code oidText}
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "UPDATE cached_data SET json_data = :json WHERE cache_key = :key AND json_data = :oidText",
            nativeQuery = true)
    int inlineLegacyRow(@Param("key") String key, @Param("oidText") String oidText, @Param("json") String json);
}
