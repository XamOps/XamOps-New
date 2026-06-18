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
}
