package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.DbAgentDataCache;
import com.xammer.cloud.domain.DbAgentMetricsHistory;
import com.xammer.cloud.dto.agent.AgentIngestPayload;
import com.xammer.cloud.dto.agent.AgentIngestResponse;
import com.xammer.cloud.dto.dbops.AgentAccessGraphDoc;
import com.xammer.cloud.dto.dbops.AgentDiagnosticDto;
import com.xammer.cloud.dto.dbops.AgentFkGraphDoc;
import com.xammer.cloud.dto.dbops.AgentLockWaitDto;
import com.xammer.cloud.dto.dbops.AgentReplicationDto;
import com.xammer.cloud.dto.dbops.AgentWaitEventDto;
import com.xammer.cloud.dto.dbops.MongoInstanceMetricsDto;
import com.xammer.cloud.dto.dbops.MongoSlowOpDto;
import com.xammer.cloud.dto.dbops.MongoIndexStatDto;
import com.xammer.cloud.dto.dbops.MongoReplStatusDto;
import com.xammer.cloud.dto.dbops.RedisInstanceMetricsDto;
import com.xammer.cloud.dto.dbops.RedisSlowLogDto;
import com.xammer.cloud.dto.dbops.RedisKeyspaceDto;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.repository.ExplainRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DbAgentService {

    private static final Logger logger = LoggerFactory.getLogger(DbAgentService.class);

    private final DbAgentConfigRepository dbAgentConfigRepository;
    private final DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private final DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final ExplainRequestRepository explainRequestRepository;
    private final DbOpsFeatureFlags dbOpsFeatureFlags;
    private final ObjectMapper objectMapper;

    public DbAgentService(
            DbAgentConfigRepository dbAgentConfigRepository,
            DbAgentDataCacheRepository dbAgentDataCacheRepository,
            DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository,
            CloudAccountRepository cloudAccountRepository,
            ExplainRequestRepository explainRequestRepository,
            DbOpsFeatureFlags dbOpsFeatureFlags) {
        this.dbAgentConfigRepository = dbAgentConfigRepository;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.dbAgentMetricsHistoryRepository = dbAgentMetricsHistoryRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.explainRequestRepository = explainRequestRepository;
        this.dbOpsFeatureFlags = dbOpsFeatureFlags;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Resolve provider account ID string (AWS account number, GCP project, Azure subscription)
     * to a CloudAccount entity.
     */
    public CloudAccount resolveAccount(String providerAccountId) {
        return cloudAccountRepository
                .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(
                        providerAccountId, providerAccountId, providerAccountId)
                .orElse(null);
    }

    /**
     * Find a registered DB agent by account + agent name.
     */
    public Optional<DbAgentConfig> findDbAgent(String providerAccountId, String agentName) {
        CloudAccount account = resolveAccount(providerAccountId);
        if (account == null) return Optional.empty();
        return dbAgentConfigRepository.findByCloudAccountIdAndAgentName(account.getId(), agentName);
    }

    /**
     * Register (or return existing) DB agent. Called by the wizard before deploying the container.
     * Returns the DbAgentConfig — idempotent.
     */
    @Transactional
    public DbAgentConfig registerDbAgent(String providerAccountId, String agentName) {
        CloudAccount account = resolveAccount(providerAccountId);
        if (account == null) {
            logger.warn("registerDbAgent: cloud account not found for id {}", providerAccountId);
            return null;
        }

        Optional<DbAgentConfig> existing = dbAgentConfigRepository
                .findByCloudAccountIdAndAgentName(account.getId(), agentName);
        if (existing.isPresent()) {
            logger.info("DB agent already registered: account={} name={}", providerAccountId, agentName);
            return existing.get();
        }

        DbAgentConfig cfg = new DbAgentConfig();
        cfg.setCloudAccountId(account.getId());
        cfg.setAgentName(agentName);
        cfg.setStatus("pending");
        DbAgentConfig saved = dbAgentConfigRepository.save(cfg);
        logger.info("Registered new DB agent: account={} name={} id={}", providerAccountId, agentName, saved.getId());
        return saved;
    }

    /**
     * Record a heartbeat from a running DB agent.
     */
    @Transactional
    public void recordHeartbeat(DbAgentConfig cfg, String agentVersion, String agentIp) {
        cfg.setLastCheckin(LocalDateTime.now());
        cfg.setStatus("active");
        if (agentVersion != null) cfg.setAgentVersion(agentVersion);
        if (agentIp != null) cfg.setAgentIp(agentIp);
        dbAgentConfigRepository.save(cfg);
        logger.debug("Heartbeat recorded for DB agent: {} ({})", cfg.getAgentName(), cfg.getId());
    }

    /**
     * Process ingest payload from a DB agent — stores batches in db_agent_data_cache.
     */
    @Transactional
    public AgentIngestResponse processIngest(DbAgentConfig cfg, AgentIngestPayload payload, String agentIp) {
        AgentIngestResponse response = AgentIngestResponse.success();

        // Update check-in
        cfg.setLastCheckin(LocalDateTime.now());
        cfg.setStatus("active");
        if (payload.getAgentVersion() != null) cfg.setAgentVersion(payload.getAgentVersion());
        if (agentIp != null) cfg.setAgentIp(agentIp);
        dbAgentConfigRepository.save(cfg);

        if (payload.getBatches() == null || payload.getBatches().isEmpty()) {
            logger.debug("DB agent {} pushed empty batches", cfg.getAgentName());
            return response;
        }

        for (AgentIngestPayload.DataBatch batch : payload.getBatches()) {
            try {
                if (batch.getItems() == null || batch.getItems().isEmpty()) continue;

                String dataJson = objectMapper.writeValueAsString(batch.getItems());
                int itemCount = batch.getItems().size();

                Optional<DbAgentDataCache> existing = dbAgentDataCacheRepository
                        .findByDbAgentIdAndDataType(cfg.getId(), batch.getCollector());

                DbAgentDataCache cache = existing.orElse(new DbAgentDataCache());
                cache.setDbAgentId(cfg.getId());
                cache.setDataType(batch.getCollector());
                cache.setDataJson(dataJson);
                cache.setItemCount(itemCount);
                cache.setAgentVersion(payload.getAgentVersion());
                // COR-17: agent rows are UTC; a missing collectedAt must fall back to UTC, not local.
                cache.setCollectedAt(batch.getCollectedAt() != null ? batch.getCollectedAt() : LocalDateTime.now(ZoneOffset.UTC));
                dbAgentDataCacheRepository.save(cache);

                // Special handling for new batch types
                if ("db-wait-events".equals(batch.getCollector())
                        || "db-diagnostics".equals(batch.getCollector())
                        || "db-explain-result".equals(batch.getCollector())
                        || "db-lock-waits".equals(batch.getCollector())
                        || "db-replication".equals(batch.getCollector())
                        || "db-access-grants".equals(batch.getCollector())
                        || "db-fk-graph".equals(batch.getCollector())
                        || "mongo-instance-metrics".equals(batch.getCollector())
                        || "mongo-slow-ops".equals(batch.getCollector())
                        || "mongo-index-stats".equals(batch.getCollector())
                        || "mongo-repl-status".equals(batch.getCollector())
                        || "redis-instance-metrics".equals(batch.getCollector())
                        || "redis-slow-log".equals(batch.getCollector())
                        || "redis-keyspace".equals(batch.getCollector())) {
                    handleSpecialBatch(batch.getCollector(), dataJson, cfg);
                }

                // Append to history for db-instance-metrics (enables time-series charts)
                if ("db-instance-metrics".equals(batch.getCollector())) {
                    DbAgentMetricsHistory history = new DbAgentMetricsHistory();
                    history.setDbAgentId(cfg.getId());
                    history.setDataType(batch.getCollector());
                    history.setDataJson(dataJson);
                    history.setItemCount(itemCount);
                    // COR-17: UTC fallback keeps history timestamps consistent with the UTC read window.
                    history.setCollectedAt(batch.getCollectedAt() != null ? batch.getCollectedAt() : LocalDateTime.now(ZoneOffset.UTC));
                    dbAgentMetricsHistoryRepository.save(history);
                }

                response.addResult(batch.getCollector(),
                        AgentIngestResponse.BatchResult.success(itemCount));
                logger.info("DB agent {} saved {} items for collector {}", cfg.getAgentName(), itemCount, batch.getCollector());

            } catch (JsonProcessingException e) {
                logger.error("DB agent ingest: error serializing batch {}: {}", batch.getCollector(), e.getMessage());
                response.addResult(batch.getCollector(),
                        AgentIngestResponse.BatchResult.error(e.getMessage()));
            }
        }

        return response;
    }

    /**
     * Handle special batch types that need custom storage logic beyond the generic upsert.
     * Called from processIngest for db-wait-events, db-diagnostics, and db-explain-result batches.
     */
    private void handleSpecialBatch(String collector, String itemsJson, DbAgentConfig agentConfig) {
        try {
            switch (collector) {
                case "db-wait-events" -> {
                    List<AgentWaitEventDto> events = objectMapper.readValue(
                        itemsJson, new TypeReference<List<AgentWaitEventDto>>() {});
                    Map<String, List<AgentWaitEventDto>> byDsn = events.stream()
                        .collect(java.util.stream.Collectors.groupingBy(AgentWaitEventDto::getDsnAlias));
                    byDsn.forEach((dsnAlias, dsnEvents) -> {
                        String cacheKey = "db-wait-events:" + agentConfig.getCloudAccountId() + ":" + dsnAlias;
                        DbAgentDataCache cache = dbAgentDataCacheRepository
                            .findByDbAgentIdAndDataType(agentConfig.getId(), cacheKey)
                            .orElseGet(() -> {
                                DbAgentDataCache c = new DbAgentDataCache();
                                c.setDbAgentId(agentConfig.getId());
                                c.setDataType(cacheKey);
                                return c;
                            });
                        try {
                            cache.setDataJson(objectMapper.writeValueAsString(dsnEvents));
                            cache.setCollectedAt(LocalDateTime.now());
                            dbAgentDataCacheRepository.save(cache);
                        } catch (Exception ex) {
                            logger.warn("db-wait-events cache write failed for {}: {}", dsnAlias, ex.getMessage());
                        }
                    });
                }
                case "db-diagnostics" -> {
                    List<AgentDiagnosticDto> diags = objectMapper.readValue(
                        itemsJson, new TypeReference<List<AgentDiagnosticDto>>() {});
                    String cacheKey = "db-diagnostics:" + agentConfig.getCloudAccountId();
                    DbAgentDataCache cache = dbAgentDataCacheRepository
                        .findByDbAgentIdAndDataType(agentConfig.getId(), cacheKey)
                        .orElseGet(() -> {
                            DbAgentDataCache c = new DbAgentDataCache();
                            c.setDbAgentId(agentConfig.getId());
                            c.setDataType(cacheKey);
                            return c;
                        });
                    try {
                        cache.setDataJson(objectMapper.writeValueAsString(diags));
                        cache.setCollectedAt(LocalDateTime.now());
                        dbAgentDataCacheRepository.save(cache);
                    } catch (Exception ex) {
                        logger.warn("db-diagnostics cache write failed: {}", ex.getMessage());
                    }
                }
                case "db-explain-result" -> {
                    // SEC-2/3: Explain is disabled server-side (xamops.dbops.explain.enabled,
                    // default false). Ignore a late result from an in-flight command so it
                    // cannot mutate an explain_request row while the feature is off.
                    if (!dbOpsFeatureFlags.isExplainEnabled()) {
                        logger.debug("db-explain-result ignored — Explain is disabled");
                    } else {
                        List<Map<String, Object>> items = objectMapper.readValue(
                            itemsJson, new TypeReference<List<Map<String, Object>>>() {});
                        for (Map<String, Object> item : items) {
                            String commandId = (String) item.get("commandId");
                            String planJson  = (String) item.get("planJson");
                            String error     = (String) item.getOrDefault("error", "");
                            if (commandId == null) continue;
                            explainRequestRepository.findById(commandId).ifPresent(req -> {
                                req.setStatus(error != null && !error.isEmpty() ? "FAILED" : "COMPLETED");
                                req.setResultJson(planJson);
                                req.setErrorMessage(error != null && !error.isEmpty() ? error : null);
                                req.setCompletedAt(LocalDateTime.now());
                                explainRequestRepository.save(req);
                                logger.info("Explain request {} completed, status={}", commandId, req.getStatus());
                            });
                        }
                    }
                }
                case "db-lock-waits" -> {
                    // Row batch — group per DSN as a JSON array, like db-wait-events.
                    List<AgentLockWaitDto> items = objectMapper.readValue(
                        itemsJson, new TypeReference<List<AgentLockWaitDto>>() {});
                    saveGroupedByDsn(agentConfig, "db-lock-waits", items,
                        i -> ((AgentLockWaitDto)i).getDsnAlias());
                }
                case "db-replication" -> {
                    List<AgentReplicationDto> items = objectMapper.readValue(
                        itemsJson, new TypeReference<List<AgentReplicationDto>>() {});
                    saveGroupedByDsn(agentConfig, "db-replication", items,
                        i -> ((AgentReplicationDto)i).getDsnAlias());
                }
                case "db-access-grants" -> {
                    // Composite document per DSN — store each doc as a single JSON OBJECT
                    // (saveDocPerDsn), not wrapped in a one-element array.
                    List<AgentAccessGraphDoc> docs = objectMapper.readValue(
                        itemsJson, new TypeReference<List<AgentAccessGraphDoc>>() {});
                    for (AgentAccessGraphDoc doc : docs) {
                        saveDocPerDsn(agentConfig, "db-access-grants", doc, doc.getDsnAlias());
                    }
                }
                case "db-fk-graph" -> {
                    List<AgentFkGraphDoc> docs = objectMapper.readValue(
                        itemsJson, new TypeReference<List<AgentFkGraphDoc>>() {});
                    for (AgentFkGraphDoc doc : docs) {
                        saveDocPerDsn(agentConfig, "db-fk-graph", doc, doc.getDsnAlias());
                    }
                }
                case "mongo-instance-metrics" -> {
                    List<MongoInstanceMetricsDto> items = objectMapper.readValue(itemsJson, new TypeReference<List<MongoInstanceMetricsDto>>() {});
                    saveGroupedByDsn(agentConfig, "mongo-instance-metrics", items,
                        i -> ((MongoInstanceMetricsDto)i).getDsnAlias());
                }
                case "mongo-slow-ops" -> {
                    List<MongoSlowOpDto> items = objectMapper.readValue(itemsJson, new TypeReference<List<MongoSlowOpDto>>() {});
                    saveGroupedByDsn(agentConfig, "mongo-slow-ops", items,
                        i -> ((MongoSlowOpDto)i).getDsnAlias());
                }
                case "mongo-index-stats" -> {
                    List<MongoIndexStatDto> items = objectMapper.readValue(itemsJson, new TypeReference<List<MongoIndexStatDto>>() {});
                    saveGroupedByDsn(agentConfig, "mongo-index-stats", items,
                        i -> ((MongoIndexStatDto)i).getDsnAlias());
                }
                case "mongo-repl-status" -> {
                    List<MongoReplStatusDto> items = objectMapper.readValue(itemsJson, new TypeReference<List<MongoReplStatusDto>>() {});
                    saveToCache(agentConfig, "mongo-repl-status:" + agentConfig.getCloudAccountId(), items);
                }
                case "redis-instance-metrics" -> {
                    List<RedisInstanceMetricsDto> items = objectMapper.readValue(itemsJson, new TypeReference<List<RedisInstanceMetricsDto>>() {});
                    saveGroupedByDsn(agentConfig, "redis-instance-metrics", items,
                        i -> ((RedisInstanceMetricsDto)i).getDsnAlias());
                }
                case "redis-slow-log" -> {
                    List<RedisSlowLogDto> items = objectMapper.readValue(itemsJson, new TypeReference<List<RedisSlowLogDto>>() {});
                    saveGroupedByDsn(agentConfig, "redis-slow-log", items,
                        i -> ((RedisSlowLogDto)i).getDsnAlias());
                }
                case "redis-keyspace" -> {
                    List<RedisKeyspaceDto> items = objectMapper.readValue(itemsJson, new TypeReference<List<RedisKeyspaceDto>>() {});
                    saveGroupedByDsn(agentConfig, "redis-keyspace", items,
                        i -> ((RedisKeyspaceDto)i).getDsnAlias());
                }
                default -> {} // unknown batch type — ignore
            }
        } catch (Exception e) {
            logger.warn("handleSpecialBatch [{}] failed: {}", collector, e.getMessage());
        }
    }

    private void saveToCache(DbAgentConfig agentConfig, String cacheKey, Object items) {
        DbAgentDataCache cache = dbAgentDataCacheRepository
            .findByDbAgentIdAndDataType(agentConfig.getId(), cacheKey)
            .orElseGet(() -> {
                DbAgentDataCache c = new DbAgentDataCache();
                c.setDbAgentId(agentConfig.getId());
                c.setDataType(cacheKey);
                return c;
            });
        try {
            cache.setDataJson(objectMapper.writeValueAsString(items));
            cache.setCollectedAt(java.time.LocalDateTime.now());
            dbAgentDataCacheRepository.save(cache);
        } catch (Exception ex) {
            logger.warn("saveToCache [{}] failed: {}", cacheKey, ex.getMessage());
        }
    }

    private <T> void saveGroupedByDsn(DbAgentConfig agentConfig, String batchType,
            List<T> items, java.util.function.Function<Object, String> dsnExtractor) {
        items.stream()
            .collect(java.util.stream.Collectors.groupingBy(i -> dsnExtractor.apply(i)))
            .forEach((dsnAlias, group) -> saveToCache(agentConfig,
                boundedCacheKey(batchType, agentConfig.getCloudAccountId(), dsnAlias), group));
    }

    /**
     * Store a composite console document (access graph / FK graph) for one DSN as a single JSON
     * OBJECT under the bounded per-DSN key — NOT wrapped in a one-element array, so the read side
     * parses it back as the doc type directly.
     */
    private void saveDocPerDsn(DbAgentConfig agentConfig, String batchType, Object doc, String dsnAlias) {
        saveToCache(agentConfig, boundedCacheKey(batchType, agentConfig.getCloudAccountId(), dsnAlias), doc);
    }

    /** Longest {@code db_agent_data_cache.data_type} the schema allows (varchar(50)). */
    static final int MAX_DATA_TYPE_LEN = 50;

    /**
     * Build a {@code {batchType}:{accountId}:{dsnAlias}} cache key, truncating the {@code dsnAlias}
     * segment so the whole key stays within {@code data_type}'s {@code varchar(50)}.
     *
     * <p>The design body widened {@code data_type} to {@code varchar(255)} via an ALTER TABLE, but
     * that is owner-requiring DDL and the Flyway role does not own the DbOps tables on UAT/prod, so
     * owner-DDL migrations fail on deploy. The reconciliation decision keeps {@code varchar(50)} and
     * no migration, bounding the alias instead. The agent emits short {@code db-N} aliases, so this
     * never actually truncates today; it is a defensive bound (and {@code dbHost} is never in a key).
     */
    static String boundedCacheKey(String batchType, Long accountId, String dsnAlias) {
        String prefix = batchType + ":" + accountId + ":";
        String alias = dsnAlias == null ? "" : dsnAlias;
        int budget = MAX_DATA_TYPE_LEN - prefix.length();
        if (budget < 0) budget = 0;
        if (alias.length() > budget) alias = alias.substring(0, budget);
        return prefix + alias;
    }

    /**
     * Get all DB agents for an account (for the DbOps UI).
     */
    public List<DbAgentConfig> getAgentsForAccount(String providerAccountId) {
        CloudAccount account = resolveAccount(providerAccountId);
        if (account == null) return List.of();
        return dbAgentConfigRepository.findByCloudAccountId(account.getId());
    }

    /**
     * Get cached data for a DB agent.
     */
    public List<DbAgentDataCache> getCachedData(Long dbAgentId) {
        return dbAgentDataCacheRepository.findByDbAgentId(dbAgentId);
    }

    /**
     * Save connection configuration for a DB agent.
     * Creates the agent registration if it doesn't exist yet.
     *
     * <p>
     * SEC-5: the DB password is deliberately NOT a parameter and is never written. The agent
     * reads its DSN (password included) from its own {@code DB_DSN_n} environment variable on
     * the customer host; the server has no use for the value, so storing it only created a
     * plaintext liability. Existing rows are nulled by migration V20261029_1.
     */
    @Transactional
    public DbAgentConfig saveConnectionConfig(String providerAccountId, String agentName,
                                               String dbEngine, String dbHost, String dbPort,
                                               String dbName, String dbUser,
                                               String serverUrl) {
        DbAgentConfig cfg = registerDbAgent(providerAccountId, agentName);
        if (cfg == null) return null;

        cfg.setDbEngine(dbEngine);
        cfg.setDbHost(dbHost);
        cfg.setDbPort(dbPort);
        cfg.setDbName(dbName);
        cfg.setDbUser(dbUser);
        cfg.setServerUrl(serverUrl);
        return dbAgentConfigRepository.save(cfg);
    }

    /**
     * Load saved connection configuration for a DB agent.
     * Returns null if not registered yet.
     */
    public DbAgentConfig loadConnectionConfig(String providerAccountId, String agentName) {
        return findDbAgent(providerAccountId, agentName).orElse(null);
    }

    /**
     * Delete a DB agent registration and all its cached data.
     */
    @Transactional
    public boolean deleteDbAgent(String providerAccountId, String agentName) {
        Optional<DbAgentConfig> existing = findDbAgent(providerAccountId, agentName);
        if (existing.isEmpty()) return false;
        DbAgentConfig cfg = existing.get();
        dbAgentDataCacheRepository.deleteByDbAgentId(cfg.getId());
        dbAgentConfigRepository.delete(cfg);
        logger.info("Deleted DB agent: account={} name={}", providerAccountId, agentName);
        return true;
    }
}
