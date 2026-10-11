package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AgentDataCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentDataCacheRepository extends JpaRepository<AgentDataCache, Long> {

    /** What capabilities needs from a cache row, without loading its data_json payload. */
    interface TypeTimestamp {
        Long getClusterConfigId();

        String getDataType();

        /** Agent-supplied UTC wall-clock time (ingest falls back to server time when absent). */
        LocalDateTime getCollectedAt();

        Integer getItemCount();

        String getAgentVersion();
    }

    /**
     * Type, freshness, size and agent version of every cached row of these clusters, as a JPQL
     * projection so the TEXT {@code data_json} column is never read (design §11.3).
     */
    @Query("SELECT a.clusterConfigId AS clusterConfigId, a.dataType AS dataType, a.collectedAt AS collectedAt, "
            + "a.itemCount AS itemCount, a.agentVersion AS agentVersion "
            + "FROM AgentDataCache a WHERE a.clusterConfigId IN :clusterConfigIds")
    List<TypeTimestamp> findTypeTimestampsByClusterConfigIdIn(@Param("clusterConfigIds") Collection<Long> clusterConfigIds);

    /**
     * Find cached data by cluster and data type
     */
    Optional<AgentDataCache> findByClusterConfigIdAndDataType(Long clusterConfigId, String dataType);

    /**
     * Find all cached data for a cluster
     */
    List<AgentDataCache> findByClusterConfigId(Long clusterConfigId);

    /**
     * Find all cached data by type
     */
    List<AgentDataCache> findByDataType(String dataType);

    /**
     * Check if data exists for cluster and type
     */
    boolean existsByClusterConfigIdAndDataType(Long clusterConfigId, String dataType);

    /**
     * Delete all cached data for a cluster
     */
    void deleteByClusterConfigId(Long clusterConfigId);

    /**
     * Delete cached data by cluster and type
     */
    void deleteByClusterConfigIdAndDataType(Long clusterConfigId, String dataType);

    /**
     * Find stale data older than specified time
     */
    @Query("SELECT a FROM AgentDataCache a WHERE a.collectedAt < :cutoff")
    List<AgentDataCache> findStaleData(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Delete stale data older than specified time
     */
    /**
     * Deletes at most {@code batchSize} stale rows.
     *
     * Nothing pruned this table until now, so the first cleanup run has to remove however
     * much has accumulated since the deployment was created. Each row carries a TEXT
     * {@code data_json} payload, so a single unbounded DELETE would hold one long
     * transaction — and a lot of locks — against the same Postgres whose connection pool has
     * already failed in production. Callers loop over small batches instead.
     */
    @Modifying
    @Transactional // one short transaction per batch — the caller loops without holding one
    @Query(value = "DELETE FROM agent_data_cache WHERE id IN ("
            + "SELECT id FROM agent_data_cache WHERE collected_at < :cutoff LIMIT :batchSize)",
            nativeQuery = true)
    int deleteStaleDataBatch(@Param("cutoff") LocalDateTime cutoff,
                             @Param("batchSize") int batchSize);

    /**
     * Get latest collected timestamp for a cluster
     */
    @Query("SELECT MAX(a.collectedAt) FROM AgentDataCache a WHERE a.clusterConfigId = :clusterConfigId")
    LocalDateTime getLatestCollectedAt(@Param("clusterConfigId") Long clusterConfigId);

    /**
     * Count data types available for a cluster
     */
    @Query("SELECT COUNT(DISTINCT a.dataType) FROM AgentDataCache a WHERE a.clusterConfigId = :clusterConfigId")
    int countDataTypesByCluster(@Param("clusterConfigId") Long clusterConfigId);
}
