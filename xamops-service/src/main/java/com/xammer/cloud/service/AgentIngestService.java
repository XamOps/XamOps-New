package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentIngestPayload;
import com.xammer.cloud.dto.agent.AgentIngestPayload.DataBatch;
import com.xammer.cloud.dto.agent.AgentIngestResponse;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.dto.agent.AgentIngestResponse.BatchResult;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.spot.AgentBatchStoredEvent;
import com.xammer.cloud.service.spot.ServerTime;
import com.xammer.cloud.service.spot.SpotInterruptionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xammer.cloud.service.automation.workload.usage.store.UsageSampleCapture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 *
 * A stored k8s-resources snapshot is also handed to {@link UsageSampleCapture} for the hourly
 * usage rollups. That only schedules work after the ingest commits, on its own bounded thread, and
 * never throws; the push result, item count, agent status and event are unaffected by it.
 */
@Service
public class AgentIngestService {

    private static final Logger logger = LoggerFactory.getLogger(AgentIngestService.class);

    /**
     * Collectors that push a single JSON object as their payload (not a list of items).
     * For these, we store batch.getItems().get(0) as a raw object instead of the full list.
     *
     * "autoscaling-metrics"          → AutoscalingKpisDto (single KPIs object — xamops-agent)
     * "autoscaling-rebalance-status" → RebalanceStatus (single status object — xamops-automation)
     * "k8s-resources"                → K8sResources (full cluster snapshot — xamops-agent)
     * "prometheus-metrics"           → PrometheusMetrics (utilization + cost + Falco — xamops-agent)
     * "node-optimizer-report"        → NodeOptimizerReport (idle nodes + spot candidates — xamops-automation)
     * "autoscaling-consolidation-plan" → ConsolidationPlanDto (look-only plan — xamops-automation)
     *
     * All other collectors (trivy, kubescape, autoscaling-events, autoscaling-rightsizing,
     * hpa-recommendations, vpa-recommendations, autoscaling-evictor-candidates,
     * autoscaling-consolidation-runs) push a list → stored as JSON array.
     *
     * "autoscaling-consolidation-runs" is a LIST collector on purpose: the consolidation controller
     * appends one ConsolidationRunDto per run (run history), so it is deliberately NOT in
     * SINGLE_OBJECT_COLLECTORS (not a single server-owned object) and NOT in SERVER_OWNED_COLLECTORS
     * (the agent is its writer, like the look-only consolidation plan).
     */
    /**
     * Data types only XamOps writes. An agent key must not be able to replace the automation
     * policy the UI shows and the automation agent receives (design §6.6, E21).
     */
    static final Set<String> SERVER_OWNED_COLLECTORS = Set.of("autoscaling-policy");

    private static final String K8S_RESOURCES = "k8s-resources";

    private static final Set<String> SINGLE_OBJECT_COLLECTORS = Set.of(
            "autoscaling-metrics",
            "autoscaling-rebalance-status",
            "k8s-resources",
            "prometheus-metrics",
            "node-optimizer-report",
            "autoscaling-consolidation-plan"
    );

    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final AgentDataCacheRepository agentDataCacheRepository;
    private final AgentEventRepository agentEventRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${xamops.agent.api-key:xamops_DEFAULT_KEY_CHANGE_IN_PRODUCTION}")
    private String globalAgentApiKey;

    /**
     * Whether the one shared global key still authenticates.
     *
     * Defaults to true so deploying this cannot take a customer's agents offline. Every use is
     * logged with the caller's IP; once those log lines stop, set this false and the shared key
     * — which is published in the install scripts and the frontend bundle — becomes inert.
     */
    @Value("${xamops.agent.legacy-global-key.enabled:true}")
    private boolean legacyGlobalKeyEnabled;

    /** Null when constructed without it (tests): usage capture is then skipped. */
    private final UsageSampleCapture usageSampleCapture;

    public AgentIngestService(
            KubernetesClusterConfigRepository clusterConfigRepository,
            AgentDataCacheRepository agentDataCacheRepository,
            AgentEventRepository agentEventRepository,
            CloudAccountRepository cloudAccountRepository) {
        this(clusterConfigRepository, agentDataCacheRepository, agentEventRepository, cloudAccountRepository, null);
    }

    /** Without an event publisher (tests): spot-interruption tracking then observes nothing. */
    public AgentIngestService(
            KubernetesClusterConfigRepository clusterConfigRepository,
            AgentDataCacheRepository agentDataCacheRepository,
            AgentEventRepository agentEventRepository,
            CloudAccountRepository cloudAccountRepository,
            UsageSampleCapture usageSampleCapture) {
        this(clusterConfigRepository, agentDataCacheRepository, agentEventRepository, cloudAccountRepository,
                usageSampleCapture, event -> { });
    }

    @Autowired
    public AgentIngestService(
            KubernetesClusterConfigRepository clusterConfigRepository,
            AgentDataCacheRepository agentDataCacheRepository,
            AgentEventRepository agentEventRepository,
            CloudAccountRepository cloudAccountRepository,
            UsageSampleCapture usageSampleCapture,
            ApplicationEventPublisher eventPublisher) {
        this.clusterConfigRepository = clusterConfigRepository;
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.agentEventRepository = agentEventRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.usageSampleCapture = usageSampleCapture;
        this.eventPublisher = eventPublisher;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Resolves an agent API key to the identity it proves, or null when it authenticates nothing.
     *
     * Order matters: scoped keys are checked before the shared one so that an agent which has
     * been migrated is recognised as itself rather than as an anonymous legacy caller.
     *
     * Comparison against the global key uses a length-independent constant-time check. A plain
     * {@code equals} on a secret leaks its length and its matching prefix through timing, which
     * is worth avoiding on an endpoint anyone on the internet can call.
     *
     * @param apiKey the key from the Authorization header
     * @param callerIp caller address, recorded only when the legacy key is used, so the
     *                 remaining pre-migration agents can be located
     * @return the authenticated principal, or null if the key is unknown
     */
    public AgentPrincipal authenticate(String apiKey, String callerIp) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Agent authentication failed: empty API key");
            return null;
        }

        // 1. Cluster-scoped key — the column has existed since V20260217 and is already handed
        //    to customers in generated Helm values; it simply was never accepted here.
        Optional<KubernetesClusterConfig> cluster = clusterConfigRepository.findByAgentApiKey(apiKey);
        if (cluster.isPresent()) {
            KubernetesClusterConfig cfg = cluster.get();
            String providerAccountId = resolveProviderAccountId(cfg.getCloudAccountId());
            if (providerAccountId == null) {
                logger.warn("Agent key resolved to cluster {} whose cloud account {} no longer exists",
                        cfg.getClusterName(), cfg.getCloudAccountId());
                return null;
            }
            return AgentPrincipal.cluster(providerAccountId, cfg.getClusterName());
        }

        // 2. Account-scoped key — VM agents (disk, database) that have no cluster row.
        Optional<CloudAccount> account = cloudAccountRepository.findByAgentApiKey(apiKey);
        if (account.isPresent()) {
            return AgentPrincipal.account(providerAccountIdOf(account.get()));
        }

        // 3. Legacy shared key.
        if (constantTimeEquals(apiKey, globalAgentApiKey)) {
            if (!legacyGlobalKeyEnabled) {
                logger.warn("Agent authentication rejected: shared global key is disabled "
                        + "(xamops.agent.legacy-global-key.enabled=false), caller={}", callerIp);
                return null;
            }
            logger.warn("Agent authenticated with the DEPRECATED shared global key, caller={}. "
                    + "Issue this agent an account-scoped key; the shared key cannot prove which "
                    + "account it belongs to.", callerIp);
            return AgentPrincipal.legacyGlobal();
        }

        logger.warn("Agent authentication failed: unknown API key, caller={}", callerIp);
        return null;
    }

    /**
     * Kept so existing callers and tests that only need a yes/no keep working. Prefer
     * {@link #authenticate} — a boolean cannot tell a handler which account the caller owns,
     * which is what actually stops one tenant acting as another.
     */
    public boolean validateApiKey(String apiKey) {
        return authenticate(apiKey, null) != null;
    }

    /**
     * Rejects a request whose claimed identity is not covered by its key.
     *
     * This is the check that was missing: {@code clusterName} and {@code cloudAccountId} arrive
     * in the request body, and every handler trusted them. Returns null when the request is
     * allowed, or a message describing the refusal.
     */
    public String checkOwnership(AgentPrincipal principal, String claimedAccountId, String claimedClusterName) {
        if (principal == null) return "Not authenticated";
        if (claimedClusterName == null
                ? principal.canAct(claimedAccountId)
                : principal.canActOnCluster(claimedAccountId, claimedClusterName)) {
            return null;
        }
        logger.warn("Agent {} attempted to act on account={} cluster={} — refused",
                principal, claimedAccountId, claimedClusterName);
        return "This agent key is not authorised for the requested account or cluster";
    }

    private String resolveProviderAccountId(Long cloudAccountId) {
        if (cloudAccountId == null) return null;
        return cloudAccountRepository.findById(cloudAccountId)
                .map(this::providerAccountIdOf)
                .orElse(null);
    }

    private String providerAccountIdOf(CloudAccount account) {
        if (account.getAwsAccountId() != null && !account.getAwsAccountId().isBlank()) {
            return account.getAwsAccountId();
        }
        if (account.getGcpProjectId() != null && !account.getGcpProjectId().isBlank()) {
            return account.getGcpProjectId();
        }
        return account.getAzureSubscriptionId();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Find cluster config by cluster name and cloud account ID.
     * Used after API key validation to identify which cluster the agent is pushing data for.
     *
     * @param clusterName The cluster name from the payload
     * @param cloudAccountId The cloud account ID from the payload
     * @return The cluster config if found, null otherwise
     */
    public KubernetesClusterConfig findClusterConfig(String clusterName, String awsAccountId) {
        if (clusterName == null || clusterName.isEmpty() || awsAccountId == null || awsAccountId.isEmpty()) {
            logger.warn("Cannot find cluster config: missing clusterName or cloudAccountId");
            return null;
        }

        // Resolve the provider account ID string to the CloudAccount DB primary key
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(awsAccountId);
        if (accounts.isEmpty()) {
            // Also try GCP / Azure
            Optional<CloudAccount> byAny = cloudAccountRepository
                    .findByProviderAccountId(awsAccountId);
            if (byAny.isEmpty()) {
                logger.warn("Cannot find cluster config: no cloud account for id {}", awsAccountId);
                return null;
            }
            accounts = List.of(byAny.get());
        }

        for (CloudAccount account : accounts) {
            Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository
                    .findByCloudAccountIdAndClusterName(account.getId(), clusterName);
            if (configOpt.isPresent()) return configOpt.get();
        }

        logger.warn("Cluster config not found for cluster: {} accountId: {}", clusterName, awsAccountId);
        return null;
    }

    /**
     * Get the global API key (for displaying in install commands).
     * @return The global agent API key
     */
    public String getGlobalApiKey() {
        return globalAgentApiKey;
    }

    /**
     * Find the cluster config for a Kubernetes agent push, auto-registering the
     * cluster on first contact when it belongs to an AWS or Azure account.
     *
     * AKS and EKS are both onboarded agent-only: no UI creates a
     * KubernetesClusterConfig row, and the one API that does
     * (POST /api/xamops/k8s/security/configure) needs a kubeconfig plus inbound
     * reachability from this server to the cluster — neither of which an
     * agent-based install has. Without this, an agent pushes valid data every
     * interval and gets a 404 forever.
     *
     * This was Azure-only while EKS still onboarded through the kubeconfig wizard.
     * The unified install script (install-k8s.sh) moved EKS onto the same push-only
     * agent as AKS — "no inbound connections required" — which put EKS in exactly
     * the failure mode described above, so the guard now covers AWS too. GKE joined
     * them when install-k8s.sh gained {@code --provider gke}: its Quick Install is
     * the same push-only agent, and without this a GKE cluster would install
     * cleanly and then 404 on every push forever.
     *
     * DB agents already auto-register on first push (see the caller in
     * AgentController), so the trust model here is not new — any holder of the
     * global agent API key can already create rows this way.
     *
     * @param clusterName    cluster name from the agent payload
     * @param cloudAccountId provider account ID from the payload (Azure subscription ID for AKS,
     *                       12-digit account number for EKS, project ID for GKE)
     * @return the existing or newly created config, or null if the account is unknown
     *         or belongs to a provider that registers before pushing
     */
    @Transactional
    public KubernetesClusterConfig findOrAutoRegisterClusterConfig(String clusterName, String cloudAccountId) {
        KubernetesClusterConfig existing = findClusterConfig(clusterName, cloudAccountId);
        if (existing != null) {
            return existing;
        }

        CloudAccount account = null;
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(cloudAccountId);
        if (!accounts.isEmpty()) {
            account = accounts.get(0);
        } else {
            Optional<CloudAccount> byAny = cloudAccountRepository.findByProviderAccountId(cloudAccountId);
            if (byAny.isPresent()) account = byAny.get();
        }

        if (account == null) {
            logger.warn("Auto-register skipped: no cloud account for id {}", cloudAccountId);
            return null;
        }

        String provider = account.getProvider();
        if (!"Azure".equalsIgnoreCase(provider)
                && !"AWS".equalsIgnoreCase(provider)
                && !"GCP".equalsIgnoreCase(provider)) {
            logger.warn("Auto-register skipped: cluster '{}' belongs to {} account {} (AWS, Azure and GCP only)",
                    clusterName, provider, cloudAccountId);
            return null;
        }

        KubernetesClusterConfig cfg = new KubernetesClusterConfig();
        cfg.setCloudAccountId(account.getId());
        cfg.setClusterName(clusterName);
        cfg.setRegion("auto");
        cfg.setKubeconfigYaml(""); // NOT NULL constraint — agent mode never reads it
        cfg.setDataSourceMode("agent");
        cfg.setAgentStatus("active");
        cfg.setLastAgentCheckin(LocalDateTime.now());
        logger.info("Auto-registered {} cluster '{}' for account {}", provider, clusterName, cloudAccountId);
        return clusterConfigRepository.save(cfg);
    }

    /**
     * Find cluster config, or auto-create a db-only entry if not found.
     *
     * Used when the DB agent runs on EC2 (no k8s cluster) — there is no
     * pre-existing KubernetesClusterConfig row, so we create a lightweight
     * placeholder with dataSourceMode="db-only" so data can be stored.
     */
    @Transactional
    public KubernetesClusterConfig findOrCreateDbOnlyClusterConfig(String clusterName, String awsAccountId) {
        // Resolve provider account ID string → CloudAccount
        CloudAccount account = null;
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(awsAccountId);
        if (!accounts.isEmpty()) {
            account = accounts.get(0);
        } else {
            Optional<CloudAccount> byAny = cloudAccountRepository.findByProviderAccountId(awsAccountId);
            if (byAny.isPresent()) account = byAny.get();
        }

        if (account == null) {
            logger.warn("findOrCreateDbOnly: cloud account {} not found", awsAccountId);
            return null;
        }

        // Try normal lookup first
        Optional<KubernetesClusterConfig> existing = clusterConfigRepository
                .findByCloudAccountIdAndClusterName(account.getId(), clusterName);
        if (existing.isPresent()) return existing.get();

        // Auto-create a db-only placeholder
        KubernetesClusterConfig cfg = new KubernetesClusterConfig();
        cfg.setCloudAccountId(account.getId());
        cfg.setClusterName(clusterName);
        cfg.setRegion("auto");
        cfg.setKubeconfigYaml(""); // NOT NULL constraint — empty for db-only agents
        cfg.setDataSourceMode("db-only");
        cfg.setAgentStatus("active");
        cfg.setLastAgentCheckin(LocalDateTime.now());
        logger.info("Auto-created db-only cluster config for account {} cluster '{}'", awsAccountId, clusterName);
        return clusterConfigRepository.save(cfg);
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
                        if (K8S_RESOURCES.equals(batch.getCollector())) {
                            captureUsageSafely(clusterConfig, batch);
                        }
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
     * Hands a stored {@code k8s-resources} snapshot to the usage rollup. Only schedules work (after
     * commit, on its own thread) and swallows any failure: the push result never depends on it.
     */
    private void captureUsageSafely(KubernetesClusterConfig clusterConfig, DataBatch batch) {
        if (usageSampleCapture == null) {
            return;
        }
        try {
            usageSampleCapture.submit(clusterConfig.getId(), clusterConfig.getCloudAccountId(),
                    batch.getItems().get(0), batch.getCollectedAt());
        } catch (RuntimeException e) {
            logger.warn("Usage rollup capture could not be scheduled for cluster config {}: {}",
                    clusterConfig.getId(), e.toString());
        }
    }

    /**
     * Process a single data batch.
     */
    private BatchResult processBatch(Long clusterConfigId, DataBatch batch, String agentVersion)
            throws JsonProcessingException {

        if (batch.getCollector() == null || batch.getCollector().isEmpty()) {
            return BatchResult.error("Collector type not specified");
        }

        if (SERVER_OWNED_COLLECTORS.contains(batch.getCollector())) {
            logger.warn("Refused agent batch for server-owned collector {} (cluster config {})",
                    batch.getCollector(), clusterConfigId);
            return BatchResult.error("autoscaling-policy is written by XamOps, not by agents");
        }

        if (batch.getItems() == null || batch.getItems().isEmpty()) {
            return BatchResult.error("No items in batch");
        }

        // Serialize items to JSON.
        // Single-object collectors (e.g. autoscaling-metrics) store the first item directly
        // so the reading service can deserialize it as the DTO type without unwrapping a list.
        // All other collectors store the full items array.
        String dataJson;
        if (SINGLE_OBJECT_COLLECTORS.contains(batch.getCollector())) {
            dataJson = objectMapper.writeValueAsString(batch.getItems().get(0));
        } else {
            dataJson = objectMapper.writeValueAsString(batch.getItems());
        }

        // Find existing cache or create new
        Optional<AgentDataCache> existingCache = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(clusterConfigId, batch.getCollector());

        int itemCount = SINGLE_OBJECT_COLLECTORS.contains(batch.getCollector()) ? 1 : batch.getItems().size();
        // Spot-interruption removal inference compares consecutive snapshots, so keep the one being
        // replaced (and when the server received it) before it is overwritten.
        String prevJson = null;
        Instant prevReceivedAt = null;
        AgentDataCache cache;
        if (existingCache.isPresent()) {
            cache = existingCache.get();
            if (SpotInterruptionTracker.K8S_RESOURCES_COLLECTOR.equals(batch.getCollector())) {
                prevJson = cache.getDataJson();
                prevReceivedAt = ServerTime.serverInstant(cache.getReceivedAt());
            }
            cache.setDataJson(dataJson);
            cache.setItemCount(itemCount);
            cache.setAgentVersion(agentVersion);
            cache.setCollectedAt(batch.getCollectedAt() != null ? batch.getCollectedAt() : LocalDateTime.now());
        } else {
            cache = new AgentDataCache(clusterConfigId, batch.getCollector(), dataJson, itemCount);
            cache.setAgentVersion(agentVersion);
            cache.setCollectedAt(batch.getCollectedAt() != null ? batch.getCollectedAt() : LocalDateTime.now());
        }

        agentDataCacheRepository.save(cache);

        // Observed after the ingest transaction commits (SpotInterruptionTracker): publishing is all
        // ingest does, so tracking can never fail or roll back a push.
        if (SpotInterruptionTracker.COLLECTORS.contains(batch.getCollector())) {
            eventPublisher.publishEvent(new AgentBatchStoredEvent(
                    clusterConfigId, batch.getCollector(), dataJson, prevJson, prevReceivedAt));
        }

        logger.debug("Saved {} items for collector {} (cluster ID: {})",
                itemCount, batch.getCollector(), clusterConfigId);

        return BatchResult.success(itemCount);
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

    /** Rows removed per transaction when pruning. Small enough to keep lock windows short. */
    static final int CLEANUP_BATCH_SIZE = 5_000;
    /** Safety valve so a runaway loop can't run indefinitely against the database. */
    static final int CLEANUP_MAX_BATCHES = 200;

    /**
     * Cleanup old agent data (retention policy).
     *
     * Deletes in bounded batches, each in its own transaction. This is deliberately not one
     * big {@code @Transactional} delete: nothing pruned these tables until this job was
     * scheduled, so the first run may have to remove a very large backlog, and a single
     * statement would hold one long transaction and a lot of locks against the same Postgres
     * whose connection pool has already caused a production outage. Batching keeps each lock
     * window short and lets other work interleave.
     *
     * Partial progress is fine — anything left over is picked up on the next run.
     */
    public int cleanupStaleData(int retentionDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        int deletedData = deleteInBatches(
                batch -> agentDataCacheRepository.deleteStaleDataBatch(cutoff, batch), "agent_data_cache");
        int deletedEvents = deleteInBatches(
                batch -> agentEventRepository.deleteOldEventsBatch(cutoff, batch), "agent_events");

        logger.info("Cleaned up {} stale data records and {} old events (cutoff={})",
                deletedData, deletedEvents, cutoff);
        return deletedData + deletedEvents;
    }

    private int deleteInBatches(java.util.function.IntUnaryOperator deleteBatch, String label) {
        int total = 0;
        for (int i = 0; i < CLEANUP_MAX_BATCHES; i++) {
            int deleted = deleteBatch.applyAsInt(CLEANUP_BATCH_SIZE);
            total += deleted;
            // A short batch means we've caught up.
            if (deleted < CLEANUP_BATCH_SIZE) return total;
        }
        logger.warn("Cleanup of {} hit the {}-batch cap with rows still remaining; "
                        + "the rest will be removed on the next run",
                label, CLEANUP_MAX_BATCHES);
        return total;
    }
}
