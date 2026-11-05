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

    /**
     * SQL fragment: the large-object OID held by {@code <col>} when it is a legacy reference
     * (ASCII {@code [1-9][0-9]{4,9}}, value 16384..4294967295, the same rule as
     * {@code LegacyLobTextType.isCandidate}), otherwise NULL. Nested CASE keeps the casts from
     * running on text that is not a number.
     */
    String OID_OF_JSON_DATA = "CASE WHEN json_data ~ '^[1-9][0-9]{4,9}$' THEN "
            + "CASE WHEN CAST(json_data AS bigint) BETWEEN 16384 AND 4294967295 "
            + "THEN CAST(CAST(json_data AS bigint) AS oid) END END";

    /**
     * Real stored size of {@code json_data}, or null when there is no row. Lets a caller refuse to
     * load a snapshot that is too large to read safely, without reading it.
     *
     * <p>{@code json_data} holds either inline text (size = its length in characters) or the OID text of
     * a legacy large object (size = bytes in {@code pg_largeobject}, read by the {@code loid}
     * index only). A reference whose object is missing counts as 0. Reading {@code pg_largeobject}
     * needs privileges; the caller must handle the exception.
     */
    @Query(value = "SELECT CASE WHEN x.lo IS NOT NULL "
            + "THEN COALESCE((SELECT SUM(length(l.data)) FROM pg_largeobject l WHERE l.loid = x.lo), 0) "
            + "ELSE CAST(length(x.json_data) AS bigint) END "
            + "FROM (SELECT json_data, " + OID_OF_JSON_DATA + " AS lo FROM cached_data WHERE cache_key = :key) x",
            nativeQuery = true)
    Long findJsonLengthByCacheKey(@Param("key") String key);

    /**
     * Deletes the row with exactly this key and, when its {@code json_data} is a legacy large-object
     * reference to an existing object, unlinks that object too (one statement, one transaction), so no
     * orphan is left. Nothing is loaded into the JVM. A value that is not an OID only loses its row.
     *
     * @return the number of large objects unlinked (the row is deleted whenever it existed)
     */
    @Transactional
    @Query(value = "WITH d AS (DELETE FROM cached_data WHERE cache_key = :key RETURNING json_data) "
            + "SELECT CAST(count(*) AS bigint) FROM ("
            + "SELECT lo_unlink(m.oid) FROM (SELECT " + OID_OF_JSON_DATA + " AS lo FROM d) r "
            + "JOIN pg_largeobject_metadata m ON m.oid = r.lo) u",
            nativeQuery = true)
    long deleteOversizedByCacheKey(@Param("key") String key);
}
