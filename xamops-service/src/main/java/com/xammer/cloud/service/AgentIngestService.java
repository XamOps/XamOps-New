package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentIngestPayload;
import com.xammer.cloud.dto.agent.AgentIngestPayload.DataBatch;
import com.xammer.cloud.dto.agent.AgentIngestResponse;
import com.xammer.cloud.dto.agent.AgentIngestResponse.BatchResult;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Service for processing data pushed by XamOps agents.
 *
 * Flow:
 * 1. Agent authenticates via SINGLE global API key (same for all clusters)
 * 2. Agent identifies itself by clusterName + cloudAccountId in payload
 * 3. Payload is validated
 * 4. Each batch is processed and stored in agent_data_cache
 * 5. Agent status is updated
 * 6. Event is logged
 */
@Service
public class AgentIngestService {

    private static final Logger logger = LoggerFactory.getLogger(AgentIngestService.class);

    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final AgentDataCacheRepository agentDataCacheRepository;
    private final AgentEventRepository agentEventRepository;
    private final ObjectMapper objectMapper;

    @Value("${xamops.agent.api-key:xamops_DEFAULT_KEY_CHANGE_IN_PRODUCTION}")
    private String globalAgentApiKey;

    public AgentIngestService(
            KubernetesClusterConfigRepository clusterConfigRepository,
            AgentDataCacheRepository agentDataCacheRepository,
            AgentEventRepository agentEventRepository) {
        this.clusterConfigRepository = clusterConfigRepository;
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.agentEventRepository = agentEventRepository;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Validate the global API key.
     * All agents use the SAME key - they identify themselves by clusterName + cloudAccountId in payload.
     *
     * @param apiKey The API key from the Authorization header
     * @return true if valid, false otherwise
     */
    public boolean validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Agent authentication failed: empty API key");
            return false;
        }

        if (!apiKey.equals(globalAgentApiKey)) {
            logger.warn("Agent authentication failed: invalid API key");
            return false;
        }

        return true;
    }

    /**
     * Find cluster config by cluster name and cloud account ID.
     * Used after API key validation to identify which cluster the agent is pushing data for.
     *
     * @param clusterName The cluster name from the payload
     * @param cloudAccountId The cloud account ID from the payload
     * @return The cluster config if found, null otherwise
     */
    public KubernetesClusterConfig findClusterConfig(String clusterName, Long cloudAccountId) {
        if (clusterName == null || clusterName.isEmpty() || cloudAccountId == null) {
            logger.warn("Cannot find cluster config: missing clusterName or cloudAccountId");
            return null;
        }

        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository
                .findByCloudAccountIdAndClusterName(cloudAccountId, clusterName);

        if (configOpt.isEmpty()) {
            logger.warn("Cluster config not found for cluster: {} accountId: {}", clusterName, cloudAccountId);
            return null;
        }

        return configOpt.get();
    }

    /**
     * Get the global API key (for displaying in install commands).
     * @return The global agent API key
     */
    public String getGlobalApiKey() {
        return globalAgentApiKey;
    }

    /**
     * Process the agent ingest payload.
     * Stores each batch in the database and updates agent status.
     */
    @Transactional
    public AgentIngestResponse processIngest(KubernetesClusterConfig clusterConfig, AgentIngestPayload payload, String agentIp) {
        logger.info("Processing agent ingest for cluster: {} (ID: {})",
                clusterConfig.getClusterName(), clusterConfig.getId());

        AgentIngestResponse response = AgentIngestResponse.success();
        int totalItemsReceived = 0;

        try {
            // Validate payload
            if (payload.getBatches() == null || payload.getBatches().isEmpty()) {
                return AgentIngestResponse.error("No data batches provided");
            }

            // Process each batch
            for (DataBatch batch : payload.getBatches()) {
                try {
                    BatchResult result = processBatch(clusterConfig.getId(), batch, payload.getAgentVersion());
                    response.addResult(batch.getCollector(), result);
                    if (result.isSuccess()) {
                        totalItemsReceived += result.getItemsProcessed();
                    }
                } catch (Exception e) {
                    logger.error("Error processing batch {}: {}", batch.getCollector(), e.getMessage());
                    response.addResult(batch.getCollector(), BatchResult.error(e.getMessage()));
                }
            }

            // Update agent status
            updateAgentStatus(clusterConfig, payload.getAgentVersion());

            // Log event
            logAgentEvent(clusterConfig.getId(), "push_success", payload.getAgentVersion(),
                    totalItemsReceived, null, agentIp);

            logger.info("Successfully processed {} items from agent for cluster: {}",
                    totalItemsReceived, clusterConfig.getClusterName());

        } catch (Exception e) {
            logger.error("Error processing agent ingest for cluster: {}", clusterConfig.getClusterName(), e);
            logAgentEvent(clusterConfig.getId(), "push_error", payload.getAgentVersion(),
                    0, e.getMessage(), agentIp);
            return AgentIngestResponse.error("Processing failed: " + e.getMessage());
        }

        return response;
    }

    /**
     * Process a single data batch.
     */
    private BatchResult processBatch(Long clusterConfigId, DataBatch batch, String agentVersion)
            throws JsonProcessingException {

        if (batch.getCollector() == null || batch.getCollector().isEmpty()) {
            return BatchResult.error("Collector type not specified");
        }

        if (batch.getItems() == null || batch.getItems().isEmpty()) {
            return BatchResult.error("No items in batch");
        }

        // Serialize items to JSON
        String dataJson = objectMapper.writeValueAsString(batch.getItems());

        // Find existing cache or create new
        Optional<AgentDataCache> existingCache = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(clusterConfigId, batch.getCollector());

        AgentDataCache cache;
        if (existingCache.isPresent()) {
            cache = existingCache.get();
            cache.setDataJson(dataJson);
            cache.setItemCount(batch.getItems().size());
            cache.setAgentVersion(agentVersion);
            cache.setCollectedAt(batch.getCollectedAt() != null ? batch.getCollectedAt() : LocalDateTime.now());
        } else {
            cache = new AgentDataCache(clusterConfigId, batch.getCollector(), dataJson, batch.getItems().size());
            cache.setAgentVersion(agentVersion);
            cache.setCollectedAt(batch.getCollectedAt() != null ? batch.getCollectedAt() : LocalDateTime.now());
        }

        agentDataCacheRepository.save(cache);

        logger.debug("Saved {} items for collector {} (cluster ID: {})",
                batch.getItems().size(), batch.getCollector(), clusterConfigId);

        return BatchResult.success(batch.getItems().size());
    }

    /**
     * Update agent status after successful push.
     */
    private void updateAgentStatus(KubernetesClusterConfig config, String agentVersion) {
        config.setLastAgentCheckin(LocalDateTime.now());
        config.setAgentVersion(agentVersion);
        config.setAgentStatus("active");
        clusterConfigRepository.save(config);
    }

    /**
     * Log an agent event.
     */
    private void logAgentEvent(Long clusterConfigId, String eventType, String agentVersion,
                               Integer itemsReceived, String errorMessage, String agentIp) {
        AgentEvent event = new AgentEvent(clusterConfigId, eventType, agentVersion, itemsReceived);
        event.setErrorMessage(errorMessage);
        event.setAgentIp(agentIp);
        agentEventRepository.save(event);
    }

    /**
     * Get cached data for a cluster and data type.
     */
    public Optional<AgentDataCache> getCachedData(Long clusterConfigId, String dataType) {
        return agentDataCacheRepository.findByClusterConfigIdAndDataType(clusterConfigId, dataType);
    }

    /**
     * Parse cached JSON data to a specific type.
     */
    public <T> T parseCachedData(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            logger.error("Error parsing cached data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Trigger dashboard sync after agent data is received.
     * Runs asynchronously to not block the agent response.
     */
    @Async("awsTaskExecutor")
    public void triggerDashboardSync(KubernetesClusterConfig config) {
        logger.info("Triggering async dashboard sync for cluster: {}", config.getClusterName());
        // This will be called by EksDashboardSyncService to merge agent data into dashboard cache
    }

    /**
     * Check agent health for a cluster.
     */
    public boolean isAgentHealthy(Long clusterConfigId) {
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository.findById(clusterConfigId);
        if (configOpt.isEmpty()) {
            return false;
        }
        KubernetesClusterConfig config = configOpt.get();
        return config.isAgentActive();
    }

    /**
     * Get last successful push timestamp.
     */
    public LocalDateTime getLastSuccessfulPush(Long clusterConfigId) {
        return agentDataCacheRepository.getLatestCollectedAt(clusterConfigId);
    }

    /**
     * Cleanup old agent data (retention policy).
     */
    @Transactional
    public int cleanupStaleData(int retentionDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deletedData = agentDataCacheRepository.deleteStaleData(cutoff);
        int deletedEvents = agentEventRepository.deleteOldEvents(cutoff);
        logger.info("Cleaned up {} stale data records and {} old events", deletedData, deletedEvents);
        return deletedData + deletedEvents;
    }
}
