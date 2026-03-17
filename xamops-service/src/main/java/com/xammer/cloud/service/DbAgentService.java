package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.DbAgentDataCache;
import com.xammer.cloud.domain.DbAgentMetricsHistory;
import com.xammer.cloud.dto.agent.AgentIngestPayload;
import com.xammer.cloud.dto.agent.AgentIngestResponse;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class DbAgentService {

    private static final Logger logger = LoggerFactory.getLogger(DbAgentService.class);

    private final DbAgentConfigRepository dbAgentConfigRepository;
    private final DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private final DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final ObjectMapper objectMapper;

    public DbAgentService(
            DbAgentConfigRepository dbAgentConfigRepository,
            DbAgentDataCacheRepository dbAgentDataCacheRepository,
            DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository,
            CloudAccountRepository cloudAccountRepository) {
        this.dbAgentConfigRepository = dbAgentConfigRepository;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.dbAgentMetricsHistoryRepository = dbAgentMetricsHistoryRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Resolve provider account ID string (AWS account number, GCP project, Azure subscription)
     * to a CloudAccount entity.
     */
    public CloudAccount resolveAccount(String providerAccountId) {
        List<CloudAccount> byAws = cloudAccountRepository.findByAwsAccountId(providerAccountId);
        if (!byAws.isEmpty()) return byAws.get(0);

        return cloudAccountRepository.findByProviderAccountId(providerAccountId).orElse(null);
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
                cache.setCollectedAt(batch.getCollectedAt() != null ? batch.getCollectedAt() : LocalDateTime.now());
                dbAgentDataCacheRepository.save(cache);

                // Append to history for db-instance-metrics (enables time-series charts)
                if ("db-instance-metrics".equals(batch.getCollector())) {
                    DbAgentMetricsHistory history = new DbAgentMetricsHistory();
                    history.setDbAgentId(cfg.getId());
                    history.setDataType(batch.getCollector());
                    history.setDataJson(dataJson);
                    history.setItemCount(itemCount);
                    history.setCollectedAt(batch.getCollectedAt() != null ? batch.getCollectedAt() : LocalDateTime.now());
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
     */
    @Transactional
    public DbAgentConfig saveConnectionConfig(String providerAccountId, String agentName,
                                               String dbEngine, String dbHost, String dbPort,
                                               String dbName, String dbUser, String dbPassword,
                                               String serverUrl) {
        DbAgentConfig cfg = registerDbAgent(providerAccountId, agentName);
        if (cfg == null) return null;

        cfg.setDbEngine(dbEngine);
        cfg.setDbHost(dbHost);
        cfg.setDbPort(dbPort);
        cfg.setDbName(dbName);
        cfg.setDbUser(dbUser);
        if (dbPassword != null && !dbPassword.isEmpty()) cfg.setDbPassword(dbPassword);
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
}
