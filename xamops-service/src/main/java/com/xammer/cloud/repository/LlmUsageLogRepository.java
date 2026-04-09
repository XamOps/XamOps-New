package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LlmUsageLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface LlmUsageLogRepository extends JpaRepository<LlmUsageLog, Long>,
        JpaSpecificationExecutor<LlmUsageLog> {

    // KPI: Total spend in period
    @Query("SELECT COALESCE(SUM(l.estimatedCost), 0) FROM LlmUsageLog l " +
           "WHERE l.clientId = :clientId AND l.timestamp >= :since")
    Double sumCostSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // KPI: Request count in period
    @Query("SELECT COUNT(l) FROM LlmUsageLog l " +
           "WHERE l.clientId = :clientId AND l.timestamp >= :since")
    Long countRequestsSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // KPI: Input token sum
    @Query("SELECT COALESCE(SUM(l.inputTokens), 0) FROM LlmUsageLog l " +
           "WHERE l.clientId = :clientId AND l.timestamp >= :since")
    Long sumInputTokensSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // KPI: Output token sum
    @Query("SELECT COALESCE(SUM(l.outputTokens), 0) FROM LlmUsageLog l " +
           "WHERE l.clientId = :clientId AND l.timestamp >= :since")
    Long sumOutputTokensSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // KPI: Average latency
    @Query("SELECT COALESCE(AVG(l.latencyMs), 0) FROM LlmUsageLog l " +
           "WHERE l.clientId = :clientId AND l.timestamp >= :since AND l.isError = false")
    Double avgLatencySince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // Chart: Daily cost trend
    @Query(value = "SELECT DATE(l.timestamp) as day, SUM(l.estimated_cost) as cost " +
                   "FROM llm_usage_log l WHERE l.client_id = :clientId AND l.timestamp >= :since " +
                   "GROUP BY DATE(l.timestamp) ORDER BY day",
           nativeQuery = true)
    List<Object[]> dailyCostSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // Chart: Cost by provider
    @Query("SELECT l.provider, SUM(l.estimatedCost) FROM LlmUsageLog l " +
           "WHERE l.clientId = :clientId AND l.timestamp >= :since " +
           "GROUP BY l.provider ORDER BY SUM(l.estimatedCost) DESC")
    List<Object[]> costByProviderSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // Table: Model usage stats
    @Query("SELECT l.model, l.provider, COUNT(l), " +
           "COALESCE(SUM(l.inputTokens), 0) + COALESCE(SUM(l.outputTokens), 0), " +
           "COALESCE(SUM(l.estimatedCost), 0), COALESCE(AVG(l.latencyMs), 0) " +
           "FROM LlmUsageLog l WHERE l.clientId = :clientId AND l.timestamp >= :since " +
           "GROUP BY l.model, l.provider ORDER BY SUM(l.estimatedCost) DESC")
    List<Object[]> modelUsageStatsSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // Anomaly detection: daily cost per provider
    @Query(value = "SELECT DATE(l.timestamp) as day, l.provider, SUM(l.estimated_cost) as cost " +
                   "FROM llm_usage_log l WHERE l.client_id = :clientId AND l.timestamp >= :since " +
                   "GROUP BY DATE(l.timestamp), l.provider ORDER BY day",
           nativeQuery = true)
    List<Object[]> dailyCostByProviderSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // ─── Usage Analytics: Request Logs (paginated) ──────────────
    Page<LlmUsageLog> findByClientIdAndTimestampGreaterThanEqualOrderByTimestampDesc(
            Long clientId, Instant since, Pageable pageable);

    // ─── Budget: Per-provider cost ─────────────────────────────
    @Query("SELECT COALESCE(SUM(l.estimatedCost), 0) FROM LlmUsageLog l " +
           "WHERE l.clientId = :clientId AND l.providerConfigId = :providerConfigId " +
           "AND l.timestamp >= :since")
    Double sumCostByProviderConfigSince(@Param("clientId") Long clientId,
                                        @Param("providerConfigId") Long providerConfigId,
                                        @Param("since") Instant since);

    // ─── Usage Analytics: Agent breakdown ────────────────────────
    @Query("SELECT COALESCE(l.agentName, 'unknown'), COUNT(l), " +
           "COALESCE(SUM(l.inputTokens), 0) + COALESCE(SUM(l.outputTokens), 0), " +
           "COALESCE(SUM(l.estimatedCost), 0), COALESCE(AVG(l.latencyMs), 0) " +
           "FROM LlmUsageLog l WHERE l.clientId = :clientId AND l.timestamp >= :since " +
           "GROUP BY l.agentName ORDER BY SUM(l.estimatedCost) DESC")
    List<Object[]> agentUsageStatsSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // ─── Usage Analytics: Model comparison ───────────────────────
    @Query("SELECT l.model, l.provider, COUNT(l), " +
           "COALESCE(SUM(l.inputTokens), 0), COALESCE(SUM(l.outputTokens), 0), " +
           "COALESCE(SUM(l.estimatedCost), 0), COALESCE(AVG(l.latencyMs), 0), " +
           "SUM(CASE WHEN l.isError = true THEN 1 ELSE 0 END) " +
           "FROM LlmUsageLog l WHERE l.clientId = :clientId AND l.timestamp >= :since " +
           "GROUP BY l.model, l.provider ORDER BY SUM(l.estimatedCost) DESC")
    List<Object[]> modelComparisonStatsSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // ─── Usage Analytics: Token heatmap (PostgreSQL) ─────────────
    @Query(value = "SELECT EXTRACT(DOW FROM l.timestamp)::int as dow, " +
                   "EXTRACT(HOUR FROM l.timestamp)::int as hr, " +
                   "COALESCE(SUM(l.total_tokens), 0) as tokens " +
                   "FROM llm_usage_log l WHERE l.client_id = :clientId AND l.timestamp >= :since " +
                   "GROUP BY dow, hr ORDER BY dow, hr",
           nativeQuery = true)
    List<Object[]> tokenHeatmapSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    // ─── Usage Analytics: Filtered agent breakdown ───────────────
    @Query("SELECT COALESCE(l.agentName, 'unknown'), COUNT(l), " +
           "COALESCE(SUM(l.inputTokens), 0) + COALESCE(SUM(l.outputTokens), 0), " +
           "COALESCE(SUM(l.estimatedCost), 0), COALESCE(AVG(l.latencyMs), 0) " +
           "FROM LlmUsageLog l WHERE l.clientId = :clientId AND l.timestamp >= :since " +
           "AND (:provider IS NULL OR l.provider = :provider) " +
           "AND (:model IS NULL OR l.model = :model) " +
           "GROUP BY l.agentName ORDER BY SUM(l.estimatedCost) DESC")
    List<Object[]> agentUsageStatsFiltered(@Param("clientId") Long clientId,
                                           @Param("since") Instant since,
                                           @Param("provider") String provider,
                                           @Param("model") String model);

    // ─── Usage Analytics: Filtered model comparison ────────────
    @Query("SELECT l.model, l.provider, COUNT(l), " +
           "COALESCE(SUM(l.inputTokens), 0), COALESCE(SUM(l.outputTokens), 0), " +
           "COALESCE(SUM(l.estimatedCost), 0), COALESCE(AVG(l.latencyMs), 0), " +
           "SUM(CASE WHEN l.isError = true THEN 1 ELSE 0 END) " +
           "FROM LlmUsageLog l WHERE l.clientId = :clientId AND l.timestamp >= :since " +
           "AND (:provider IS NULL OR l.provider = :provider) " +
           "AND (:agent IS NULL OR l.agentName = :agent) " +
           "GROUP BY l.model, l.provider ORDER BY SUM(l.estimatedCost) DESC")
    List<Object[]> modelComparisonStatsFiltered(@Param("clientId") Long clientId,
                                                @Param("since") Instant since,
                                                @Param("provider") String provider,
                                                @Param("agent") String agent);

    // ─── Usage Analytics: Filtered token heatmap (PostgreSQL) ──
    @Query(value = "SELECT EXTRACT(DOW FROM l.timestamp)::int as dow, " +
                   "EXTRACT(HOUR FROM l.timestamp)::int as hr, " +
                   "COALESCE(SUM(l.total_tokens), 0) as tokens " +
                   "FROM llm_usage_log l WHERE l.client_id = :clientId AND l.timestamp >= :since " +
                   "AND (:provider IS NULL OR l.provider = CAST(:provider AS VARCHAR)) " +
                   "AND (:model IS NULL OR l.model = CAST(:model AS VARCHAR)) " +
                   "AND (:agent IS NULL OR l.agent_name = CAST(:agent AS VARCHAR)) " +
                   "GROUP BY dow, hr ORDER BY dow, hr",
           nativeQuery = true)
    List<Object[]> tokenHeatmapFiltered(@Param("clientId") Long clientId,
                                        @Param("since") Instant since,
                                        @Param("provider") String provider,
                                        @Param("model") String model,
                                        @Param("agent") String agent);

    // ─── Budget: Daily cost by provider config ──────────────────
    @Query(value = "SELECT CAST(l.timestamp AS DATE) as dt, COALESCE(SUM(l.estimated_cost), 0) " +
                   "FROM llm_usage_log l WHERE l.client_id = :clientId " +
                   "AND l.provider_config_id = :providerConfigId AND l.timestamp >= :since " +
                   "GROUP BY dt ORDER BY dt",
           nativeQuery = true)
    List<Object[]> dailyCostByProviderConfigSince(@Param("clientId") Long clientId,
                                                   @Param("providerConfigId") Long providerConfigId,
                                                   @Param("since") Instant since);

    // ─── Usage Analytics: Filter options ─────────────────────────
    @Query("SELECT DISTINCT l.provider FROM LlmUsageLog l WHERE l.clientId = :clientId")
    List<String> findDistinctProviders(@Param("clientId") Long clientId);

    @Query("SELECT DISTINCT l.model FROM LlmUsageLog l WHERE l.clientId = :clientId")
    List<String> findDistinctModels(@Param("clientId") Long clientId);

    @Query("SELECT DISTINCT l.agentName FROM LlmUsageLog l WHERE l.clientId = :clientId AND l.agentName IS NOT NULL")
    List<String> findDistinctAgentNames(@Param("clientId") Long clientId);
}
