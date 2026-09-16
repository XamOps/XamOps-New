package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CachedData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
     * The text of a PostgreSQL large object, or null when no such object exists.
     *
     * <p>Reads rows written while {@link CachedData#getJsonData()} was annotated
     * {@code @Lob}. On PostgreSQL that stored the JSON as a large object and put only its
     * OID in the TEXT column, so those rows hold a bare integer that nothing resolves once
     * the annotation is gone. Prod still writes in that form, so the read path has to
     * understand both until every environment is migrated.
     *
     * <p>{@code lo_get} is the server-side function: it returns {@code bytea} from an
     * ordinary SELECT and never opens a LOB stream. That matters, because the streaming
     * API is what fails with "Large Objects may not be used in auto-commit mode" under
     * Hibernate 7 — the very error that removing {@code @Lob} was fixing. Resolving the
     * OID this way reads the old rows without reintroducing it.
     *
     * <p>Selecting through {@code pg_largeobject_metadata} rather than calling
     * {@code lo_get} directly means a missing OID yields no rows instead of raising
     * {@code large object ... does not exist}, which would abort the surrounding
     * transaction.
     */
    @Query(value = "SELECT convert_from(lo_get(m.oid), 'UTF8') FROM pg_largeobject_metadata m"
            + " WHERE m.oid = CAST(:oid AS oid)", nativeQuery = true)
    String findLargeObjectText(@Param("oid") String oid);
}
