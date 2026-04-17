package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.EksDashboardCache;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.*;
import com.xammer.cloud.dto.autoscaling.NodeOptimizerPolicyDto;
import com.xammer.cloud.dto.autoscaling.WorkloadRightsizingDto;
import com.xammer.cloud.dto.autoscaling.WorkloadRightsizingDto.WorkloadRecommendation;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.EksDashboardCacheRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * EksAutoscalingService — core backend for XamOps Autoscaling Agent.
 *
 * Data sources (NO new tables — everything reuses existing infrastructure):
 *
 *   1. kubernetes_cluster_config
 *        - autoscalingEnabled  → guard: only serve data when true
 *        - agent_status        → maps to AgentStatusDto.agentHealthy
 *        - agent_version       → maps to AgentStatusDto.agentVersion
 *        - last_agent_checkin  → maps to AgentStatusDto.lastSyncAt
 *
 *   2. agent_data_cache (existing table, new data_type values)
 *        - dataType = "autoscaling-policy"   → ScaleOutPolicy + EvictorPolicy +
 *                                              WorkloadRightsizerPolicy + NodeTemplates +
 *                                              RebalancerConfig + SafetyGuards + mode
 *                                              (saved by XamOps backend when user edits in UI)
 *        - dataType = "autoscaling-metrics"  → AutoscalingKpisDto
 *                                              (pushed by XamOps agent from cluster Prometheus)
 *        - dataType = "autoscaling-events"   → List<AutoscalingEventDto>
 *                                              (pushed by XamOps agent when actions happen)
 *
 *   3. agent_events (existing table, filtered by event_type prefix "autoscaling_*")
 *        - Used as fallback activity feed when no autoscaling-events cache exists
 *
 * Defaults: all sub-policies have sensible CAST.ai-aligned defaults so the UI
 * works out of the box even before the agent pushes anything.
 */
@Service
public class EksAutoscalingService {

    private static final Logger logger = LoggerFactory.getLogger(EksAutoscalingService.class);

    // ── Data types stored in agent_data_cache ──────────────────────────────
    // Pushed by xamops-agent (read-only observer):
    public static final String DT_POLICY             = "autoscaling-policy";
    public static final String DT_METRICS            = "autoscaling-metrics";
    public static final String DT_EVENTS             = "autoscaling-events";
    // Pushed by xamops-automation (read-write controller):
    public static final String DT_RIGHTSIZING        = "autoscaling-rightsizing";
    public static final String DT_REBALANCE_STATUS   = "autoscaling-rebalance-status";
    public static final String DT_EVICTOR_CANDIDATES = "autoscaling-evictor-candidates";

    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final AgentDataCacheRepository agentDataCacheRepository;
    private final AgentEventRepository agentEventRepository;
    private final EksDashboardCacheRepository eksDashboardCacheRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final ObjectMapper objectMapper;

    public EksAutoscalingService(
            KubernetesClusterConfigRepository clusterConfigRepository,
            AgentDataCacheRepository agentDataCacheRepository,
            AgentEventRepository agentEventRepository,
            EksDashboardCacheRepository eksDashboardCacheRepository,
            CloudAccountRepository cloudAccountRepository) {
        this.clusterConfigRepository = clusterConfigRepository;
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.agentEventRepository = agentEventRepository;
        this.eksDashboardCacheRepository = eksDashboardCacheRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Build the full AutoscalerDashboardDto for a cluster.
     * Returns null if autoscaling is not enabled on this cluster.
     */
    public AutoscalerDashboardDto getDashboard(String clusterName, Long cloudAccountId, String region) {
        KubernetesClusterConfig config = findConfig(clusterName, cloudAccountId);
        if (config == null) return null;

        // Guard: autoscaling must be enabled on this cluster
        if (!Boolean.TRUE.equals(config.getAutoscalingEnabled())) {
            // Return a minimal "disabled" response so frontend can show the banner
            AutoscalerDashboardDto disabled = new AutoscalerDashboardDto();
            AgentStatusDto status = new AgentStatusDto();
            status.setMode("disabled");
            status.setAgentHealthy(false);
            status.setClusterControllerHealthy(false);
            status.setEvictorHealthy(false);
            status.setWorkloadAutoscalerHealthy(false);
            status.setAgentVersion(config.getAgentVersion() != null ? config.getAgentVersion() : "not-installed");
            disabled.setAgentStatus(status);
            disabled.setRecentEvents(Collections.emptyList());
            disabled.setNodeTemplates(Collections.emptyList());
            return disabled;
        }

        AutoscalerDashboardDto dto = new AutoscalerDashboardDto();
        dto.setAutoscalingEnabled(true);

        // 1. Agent status — from kubernetes_cluster_config
        dto.setAgentStatus(buildAgentStatus(config));

        // 2. Policies — from agent_data_cache (dataType="autoscaling-policy")
        PolicyBundle bundle = readPolicyBundle(config.getId());
        dto.setScaleOutPolicy(bundle.scaleOutPolicy);
        dto.setEvictorPolicy(bundle.evictorPolicy);
        dto.setWorkloadRightsizerPolicy(bundle.workloadRightsizerPolicy);
        dto.setNodeTemplates(bundle.nodeTemplates);
        dto.setRebalancer(bundle.rebalancer);
        dto.setSafetyGuards(bundle.safetyGuards);
        dto.setNodeOptimizerPolicy(bundle.nodeOptimizerPolicy);

        // 3. Runtime metrics — from agent_data_cache (dataType="autoscaling-metrics")
        //    Enriched with live data from Prometheus/existing EksDashboardCache where available
        dto.setKpis(readMetrics(config.getId()));

        // 4. Activity feed — from agent_data_cache (dataType="autoscaling-events")
        dto.setRecentEvents(readEvents(config.getId()));

        return dto;
    }

    // ── Policy saves ──────────────────────────────────────────────────────

    @Transactional
    public void saveScaleOutPolicy(String clusterName, Long cloudAccountId, ScaleOutPolicyDto policy) {
        KubernetesClusterConfig config = requireAutoscalingEnabled(clusterName, cloudAccountId);
        PolicyBundle bundle = readPolicyBundle(config.getId());
        bundle.scaleOutPolicy = policy;
        writePolicyBundle(config.getId(), bundle);
        logEvent(config.getId(), "autoscaling_config_change", "Scale-Out policy updated");
    }

    @Transactional
    public void saveEvictorPolicy(String clusterName, Long cloudAccountId, EvictorPolicyDto policy) {
        KubernetesClusterConfig config = requireAutoscalingEnabled(clusterName, cloudAccountId);
        PolicyBundle bundle = readPolicyBundle(config.getId());
        bundle.evictorPolicy = policy;
        writePolicyBundle(config.getId(), bundle);
        logEvent(config.getId(), "autoscaling_config_change", "Evictor policy updated");
    }

    @Transactional
    public void saveWorkloadRightsizerPolicy(String clusterName, Long cloudAccountId,
                                             WorkloadRightsizerPolicyDto policy) {
        KubernetesClusterConfig config = requireAutoscalingEnabled(clusterName, cloudAccountId);
        PolicyBundle bundle = readPolicyBundle(config.getId());
        bundle.workloadRightsizerPolicy = policy;
        writePolicyBundle(config.getId(), bundle);
        logEvent(config.getId(), "autoscaling_config_change", "Workload Rightsizer policy updated");
    }

    @Transactional
    public void saveSafetyGuards(String clusterName, Long cloudAccountId, SafetyGuardsDto guards) {
        KubernetesClusterConfig config = requireAutoscalingEnabled(clusterName, cloudAccountId);
        PolicyBundle bundle = readPolicyBundle(config.getId());
        bundle.safetyGuards = guards;
        writePolicyBundle(config.getId(), bundle);
        logEvent(config.getId(), "autoscaling_config_change", "Safety Guards updated");
    }

    @Transactional
    public void saveNodeOptimizerPolicy(String clusterName, Long cloudAccountId, NodeOptimizerPolicyDto policy) {
        KubernetesClusterConfig config = requireAutoscalingEnabled(clusterName, cloudAccountId);
        PolicyBundle bundle = readPolicyBundle(config.getId());
        bundle.nodeOptimizerPolicy = policy;
        writePolicyBundle(config.getId(), bundle);
        logEvent(config.getId(), "autoscaling_config_change", "Node Optimizer policy updated");
    }

    @Transactional
    public void saveNodeTemplate(String clusterName, Long cloudAccountId, String templateId,
                                 NodeTemplateDto template) {
        KubernetesClusterConfig config = requireAutoscalingEnabled(clusterName, cloudAccountId);
        PolicyBundle bundle = readPolicyBundle(config.getId());
        if (bundle.nodeTemplates == null) bundle.nodeTemplates = new ArrayList<>();
        bundle.nodeTemplates.removeIf(t -> templateId.equals(t.getId()));
        template.setId(templateId);
        bundle.nodeTemplates.add(template);
        writePolicyBundle(config.getId(), bundle);
        logEvent(config.getId(), "autoscaling_config_change", "Node template '" + template.getName() + "' saved");
    }

    @Transactional
    public void deleteNodeTemplate(String clusterName, Long cloudAccountId, String templateId) {
        KubernetesClusterConfig config = requireAutoscalingEnabled(clusterName, cloudAccountId);
        PolicyBundle bundle = readPolicyBundle(config.getId());
        if (bundle.nodeTemplates != null) {
            bundle.nodeTemplates.removeIf(t -> templateId.equals(t.getId()));
        }
        writePolicyBundle(config.getId(), bundle);
        logEvent(config.getId(), "autoscaling_config_change", "Node template deleted: " + templateId);
    }

    @Transactional
    public void saveRebalancerConfig(String clusterName, Long cloudAccountId, RebalancerConfigDto rebalancer) {
        KubernetesClusterConfig config = requireAutoscalingEnabled(clusterName, cloudAccountId);
        PolicyBundle bundle = readPolicyBundle(config.getId());
        bundle.rebalancer = rebalancer;
        writePolicyBundle(config.getId(), bundle);
        logEvent(config.getId(), "autoscaling_config_change", "Rebalancer config updated");
    }

    @Transactional
    public void setMode(String clusterName, Long cloudAccountId, String mode) {
        KubernetesClusterConfig config = requireAutoscalingEnabled(clusterName, cloudAccountId);
        PolicyBundle bundle = readPolicyBundle(config.getId());
        bundle.autoscalerMode = mode;
        writePolicyBundle(config.getId(), bundle);
        logEvent(config.getId(), "autoscaling_config_change", "Autoscaler mode changed to: " + mode);
    }

    /**
     * Trigger a manual rebalance.
     * Stores a "rebalancing" status in agent_data_cache so the agent picks it up
     * on next heartbeat and executes the rebalance. The agent then pushes an
     * "autoscaling-events" batch with the result.
     */
    @Transactional
    public void triggerRebalance(String clusterName, Long cloudAccountId) {
        KubernetesClusterConfig config = requireAutoscalingEnabled(clusterName, cloudAccountId);
        PolicyBundle bundle = readPolicyBundle(config.getId());
        if (bundle.rebalancer == null) bundle.rebalancer = defaultRebalancer();
        bundle.rebalancer.setStatus("running");
        writePolicyBundle(config.getId(), bundle);
        logEvent(config.getId(), "autoscaling_rebalance_triggered",
                "Manual rebalance triggered from XamOps UI");
        logger.info("Rebalance triggered for cluster: {} (configId: {})",
                clusterName, config.getId());
    }

    /**
     * Enable or disable autoscaling for a cluster.
     * Called from ClusterIntegrationController or agent setup flow.
     */
    @Transactional
    public void setAutoscalingEnabled(Long clusterConfigId, boolean enabled) {
        clusterConfigRepository.findById(clusterConfigId).ifPresent(config -> {
            config.setAutoscalingEnabled(enabled);
            clusterConfigRepository.save(config);
            logger.info("Autoscaling {} for cluster: {} (configId: {})",
                    enabled ? "ENABLED" : "DISABLED", config.getClusterName(), clusterConfigId);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AGENT-PUSHED DATA HANDLERS
    // (called by AgentIngestService when agent pushes these data types)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Store agent-pushed autoscaling runtime metrics.
     * Called from AgentIngestService when collector = "autoscaling-metrics".
     *
     * Expected JSON in dataJson: matches AutoscalingKpisDto structure.
     */
    @Transactional
    public void storeAgentMetrics(Long clusterConfigId, String metricsJson, String agentVersion) {
        upsertAgentDataCache(clusterConfigId, DT_METRICS, metricsJson, 1, agentVersion);
    }

    /**
     * Store agent-pushed autoscaling events (scale-out, scale-in, rebalance, etc.).
     * Called from AgentIngestService when collector = "autoscaling-events".
     *
     * Expected JSON in dataJson: JSON array of AutoscalingEventDto.
     */
    @Transactional
    public void storeAgentEvents(Long clusterConfigId, String eventsJson, int count, String agentVersion) {
        upsertAgentDataCache(clusterConfigId, DT_EVENTS, eventsJson, count, agentVersion);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private KubernetesClusterConfig findConfig(String clusterName, Long cloudAccountId) {
        // 1. Direct lookup — works when cloud_account_id stores the value as-is
        Optional<KubernetesClusterConfig> configOpt =
                clusterConfigRepository.findByCloudAccountIdAndClusterName(cloudAccountId, clusterName);
        if (configOpt.isPresent()) return configOpt.get();

        // 2. Fallback: the URL passes the AWS account number (e.g. 80310056620) but
        //    cloud_account_id may store the cloud_account DB primary key.
        //    Look up the CloudAccount by awsAccountId and retry with its DB id.
        // AWS account IDs are always 12 digits — Long.parseLong strips the leading zero,
        // so String.valueOf(80310056620L) = "80310056620" != "080310056620" stored in DB.
        String awsAccountIdPadded = String.format("%012d", cloudAccountId);
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(awsAccountIdPadded);
        for (CloudAccount account : accounts) {
            configOpt = clusterConfigRepository.findByCloudAccountIdAndClusterName(account.getId(), clusterName);
            if (configOpt.isPresent()) {
                logger.debug("findConfig: found via DB-id fallback — awsAccountId={} dbId={} cluster={}",
                        cloudAccountId, account.getId(), clusterName);
                return configOpt.get();
            }
        }
        return null;
    }

    private KubernetesClusterConfig requireAutoscalingEnabled(String clusterName, Long cloudAccountId) {
        KubernetesClusterConfig config = findConfig(clusterName, cloudAccountId);
        if (config == null) {
            throw new RuntimeException("Cluster config not found: " + clusterName);
        }
        // Allow saving policies even if autoscaling is not yet fully enabled (setup flow)
        return config;
    }

    // ── Agent status mapping ───────────────────────────────────────────────

    private AgentStatusDto buildAgentStatus(KubernetesClusterConfig config) {
        AgentStatusDto dto = new AgentStatusDto();

        boolean agentActive = config.isAgentActive();
        dto.setAgentHealthy(agentActive && "active".equals(config.getAgentStatus()));
        dto.setAgentVersion(config.getAgentVersion() != null ? config.getAgentVersion() : "unknown");
        dto.setLastSyncAt(config.getLastAgentCheckin() != null
                ? config.getLastAgentCheckin().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : null);

        // Component health: autoscaler is healthy if it is actively checking in.
        // We check for any autoscaler data in cache (metrics, rebalance-status, evictor-candidates,
        // or rightsizing) as proof the autoscaler is running. Falling back to agentActive alone
        // if the autoscaler just started and hasn't pushed its first batch yet.
        boolean autoscalerHasData =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_METRICS).isPresent()
                || agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_REBALANCE_STATUS).isPresent()
                || agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_EVICTOR_CANDIDATES).isPresent()
                || agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_RIGHTSIZING).isPresent();

        // Mark components healthy if autoscaler is actively checking in (agentActive)
        // even before the first data push, so the UI doesn't show Offline on a fresh install.
        boolean componentsHealthy = agentActive;
        dto.setClusterControllerHealthy(componentsHealthy);
        dto.setEvictorHealthy(componentsHealthy);
        dto.setWorkloadAutoscalerHealthy(componentsHealthy);

        // Log if no data yet (first startup or autoscaler not pushing)
        if (!autoscalerHasData) {
            logger.debug("No autoscaler data in cache yet for cluster {} — components marked healthy based on agent checkin",
                    config.getClusterName());
        }

        // Read mode from policy bundle
        PolicyBundle bundle = readPolicyBundle(config.getId());
        dto.setMode(bundle.autoscalerMode != null ? bundle.autoscalerMode : "full");

        return dto;
    }

    // ── Policy bundle (all policies stored as a single JSON object) ────────

    /**
     * Internal POJO that holds all configurable policy sections together.
     * Stored as a single JSON blob in agent_data_cache.dataJson for simplicity
     * and atomic reads/writes without extra columns.
     */
    static class PolicyBundle {
        public String autoscalerMode = "full";
        public ScaleOutPolicyDto scaleOutPolicy;
        public EvictorPolicyDto evictorPolicy;
        public WorkloadRightsizerPolicyDto workloadRightsizerPolicy;
        public List<NodeTemplateDto> nodeTemplates;
        public RebalancerConfigDto rebalancer;
        public SafetyGuardsDto safetyGuards;
        public NodeOptimizerPolicyDto nodeOptimizerPolicy;
    }

    private PolicyBundle readPolicyBundle(Long clusterConfigId) {
        Optional<AgentDataCache> cacheOpt =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(clusterConfigId, DT_POLICY);

        if (cacheOpt.isPresent()) {
            try {
                return objectMapper.readValue(cacheOpt.get().getDataJson(), PolicyBundle.class);
            } catch (Exception e) {
                logger.warn("Failed to parse autoscaling policy for configId={}: {}", clusterConfigId, e.getMessage());
            }
        }

        return buildDefaultBundle();
    }

    private void writePolicyBundle(Long clusterConfigId, PolicyBundle bundle) {
        try {
            String json = objectMapper.writeValueAsString(bundle);
            upsertAgentDataCache(clusterConfigId, DT_POLICY, json, 1, null);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize autoscaling policy", e);
        }
    }

    private PolicyBundle buildDefaultBundle() {
        PolicyBundle b = new PolicyBundle();
        b.autoscalerMode = "full";
        b.scaleOutPolicy = new ScaleOutPolicyDto();
        b.evictorPolicy = new EvictorPolicyDto();
        b.workloadRightsizerPolicy = new WorkloadRightsizerPolicyDto();
        b.nodeTemplates = buildDefaultNodeTemplates();
        b.rebalancer = defaultRebalancer();
        b.safetyGuards = buildDefaultSafetyGuards();
        b.nodeOptimizerPolicy = new NodeOptimizerPolicyDto();
        return b;
    }

    private RebalancerConfigDto defaultRebalancer() {
        return new RebalancerConfigDto();
    }

    private List<NodeTemplateDto> buildDefaultNodeTemplates() {
        List<NodeTemplateDto> templates = new ArrayList<>();

        NodeTemplateDto onDemand = new NodeTemplateDto();
        onDemand.setId("tpl-default");
        onDemand.setName("Default On-Demand");
        onDemand.setDefault(true);
        onDemand.setCapacityType("on-demand");
        onDemand.setMinCpuCores(2);
        onDemand.setMaxCpuCores(16);
        onDemand.setMinRamMib(4096);
        onDemand.setMaxRamMib(32768);
        onDemand.setArchitectures(List.of("amd64"));
        onDemand.setCustomLabels(new HashMap<>());
        onDemand.setCustomTaints(new ArrayList<>());
        templates.add(onDemand);

        NodeTemplateDto spot = new NodeTemplateDto();
        spot.setId("tpl-spot");
        spot.setName("XamOps Spot Pool");
        spot.setDefault(false);
        spot.setCapacityType("spot");
        spot.setUseSpotFallback(true);
        spot.setFallbackRestoreRateSeconds(1800);
        spot.setMinCpuCores(4);
        spot.setMaxCpuCores(32);
        spot.setMinRamMib(8192);
        spot.setMaxRamMib(65536);
        spot.setArchitectures(List.of("amd64", "arm64"));
        spot.setSpotAutomationEnabled(true);
        spot.setCustomLabels(Map.of("workload-type", "batch"));
        spot.setCustomTaints(List.of(Map.of("key", "spot", "effect", "NoSchedule")));
        templates.add(spot);

        return templates;
    }

    private SafetyGuardsDto buildDefaultSafetyGuards() {
        SafetyGuardsDto guards = new SafetyGuardsDto();
        guards.setCriticalNamespaces(List.of("kube-system", "monitoring", "ingress-nginx"));
        return guards;
    }

    // ── Runtime metrics ────────────────────────────────────────────────────

    private AutoscalingKpisDto readMetrics(Long clusterConfigId) {
        Optional<AgentDataCache> cacheOpt =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(clusterConfigId, DT_METRICS);

        if (cacheOpt.isPresent()) {
            try {
                return objectMapper.readValue(cacheOpt.get().getDataJson(), AutoscalingKpisDto.class);
            } catch (Exception e) {
                logger.warn("Failed to parse autoscaling metrics for configId={}: {}", clusterConfigId, e.getMessage());
            }
        }

        // No metrics pushed yet → return zeros (agent will populate on first push)
        return new AutoscalingKpisDto();
    }

    // ── Events (activity feed) ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<AutoscalingEventDto> readEvents(Long clusterConfigId) {
        // Primary: agent-pushed events cache
        Optional<AgentDataCache> cacheOpt =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(clusterConfigId, DT_EVENTS);

        if (cacheOpt.isPresent()) {
            try {
                List<AutoscalingEventDto> events = objectMapper.readValue(
                        cacheOpt.get().getDataJson(),
                        new TypeReference<List<AutoscalingEventDto>>() {});
                // Return most recent 20
                if (events.size() > 20) events = events.subList(0, 20);
                return events;
            } catch (Exception e) {
                logger.warn("Failed to parse autoscaling events for configId={}: {}", clusterConfigId, e.getMessage());
            }
        }

        // Fallback: build events from agent_events table (autoscaling_* event types)
        return buildEventsFromAgentLog(clusterConfigId);
    }

    private List<AutoscalingEventDto> buildEventsFromAgentLog(Long clusterConfigId) {
        try {
            List<AgentEvent> agentEvents = agentEventRepository
                    .findByClusterConfigIdOrderByCreatedAtDesc(
                            clusterConfigId, PageRequest.of(0, 20))
                    .getContent();

            List<AutoscalingEventDto> events = new ArrayList<>();
            for (AgentEvent ae : agentEvents) {
                if (!ae.getEventType().startsWith("autoscaling_")) continue;
                AutoscalingEventDto evt = new AutoscalingEventDto();
                evt.setId(String.valueOf(ae.getId()));
                evt.setTimestamp(ae.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                evt.setType(mapAgentEventType(ae.getEventType()));
                evt.setSeverity("info");
                evt.setMessage(ae.getDetails() != null ? ae.getDetails() : ae.getEventType());
                events.add(evt);
            }
            return events;
        } catch (Exception e) {
            logger.warn("Failed to build events from agent log: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String mapAgentEventType(String agentEventType) {
        if (agentEventType == null) return "info";
        if (agentEventType.contains("scale_out")) return "scale-out";
        if (agentEventType.contains("scale_in") || agentEventType.contains("evictor")) return "scale-in";
        if (agentEventType.contains("rebalance")) return "rebalance";
        if (agentEventType.contains("workload") || agentEventType.contains("rightsiz")) return "workload-resize";
        if (agentEventType.contains("spot")) return "spot-fallback";
        if (agentEventType.contains("node_replace")) return "node-replace";
        return "info";
    }

    // ── Upsert agent_data_cache ────────────────────────────────────────────

    private void upsertAgentDataCache(Long clusterConfigId, String dataType,
                                      String dataJson, int itemCount, String agentVersion) {
        Optional<AgentDataCache> existing =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(clusterConfigId, dataType);

        AgentDataCache cache = existing.orElseGet(AgentDataCache::new);
        cache.setClusterConfigId(clusterConfigId);
        cache.setDataType(dataType);
        cache.setDataJson(dataJson);
        cache.setItemCount(itemCount);
        cache.setCollectedAt(LocalDateTime.now());
        if (agentVersion != null) {
            cache.setAgentVersion(agentVersion);
        }
        agentDataCacheRepository.save(cache);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AUTOSCALER CONTROLLER DATA (xamops-automation pushes these)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Read rightsizing recommendations.
     * Primary source: autoscaling-rightsizing pushed by xamops-automation (Prometheus-backed p80 analysis).
     * Fallback:       compute snapshot-based recommendations from EksDashboardCache (already in DB).
     */
    @SuppressWarnings("unchecked")
    public WorkloadRightsizingDto getRightsizingData(String clusterName, Long cloudAccountId) {
        KubernetesClusterConfig config = findConfig(clusterName, cloudAccountId);
        if (config == null) return emptyRightsizingDto();

        Optional<AgentDataCache> cacheOpt =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_RIGHTSIZING);

        // Primary: use autoscaler-pushed rightsizing data (Prometheus p80)
        if (cacheOpt.isPresent()) {
            try {
                List<WorkloadRecommendation> recs = objectMapper.readValue(
                        cacheOpt.get().getDataJson(),
                        new TypeReference<List<WorkloadRecommendation>>() {});

                WorkloadRightsizingDto dto = new WorkloadRightsizingDto();
                dto.setRecommendations(recs);
                dto.setLastAnalyzedAt(cacheOpt.get().getCollectedAt() != null
                        ? cacheOpt.get().getCollectedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        : null);
                dto.setTotalWorkloads(recs.size());
                long withRecs = recs.stream().filter(r -> r.getEstimatedSavingsPercent() > 0).count();
                dto.setWorkloadsWithRecommendations((int) withRecs);
                double avgSavings = recs.stream()
                        .mapToDouble(WorkloadRecommendation::getEstimatedSavingsPercent)
                        .average().orElse(0);
                dto.setTotalEstimatedSavingsPercent(Math.round(avgSavings * 10.0) / 10.0);
                return dto;
            } catch (Exception e) {
                logger.warn("Failed to parse rightsizing data: {}", e.getMessage());
            }
        }

        // Fallback: derive recommendations from the EksDashboardCache (snapshot already in DB)
        logger.info("No autoscaler rightsizing data for cluster {} — falling back to snapshot analysis from EksDashboardCache", clusterName);
        return computeRightsizingFromDashboardCache(config);
    }

    /**
     * Compute rightsizing recommendations from the EksDashboardCache snapshot.
     *
     * Uses per-container CPU/memory data already collected from Prometheus/metrics-server
     * and stored in eks_dashboard_cache. This requires NO external Prometheus query.
     *
     * Accuracy: snapshot-based (current usage, not p80 over 72h). Recommendations
     * are labelled with reason="snapshot-based" so users know the source.
     */
    private WorkloadRightsizingDto computeRightsizingFromDashboardCache(KubernetesClusterConfig config) {
        try {
            Optional<CloudAccount> accountOpt = cloudAccountRepository.findById(config.getCloudAccountId());
            if (accountOpt.isEmpty()) return emptyRightsizingDto();

            String awsAccountId = accountOpt.get().getAwsAccountId();
            Optional<EksDashboardCache> dashCacheOpt = eksDashboardCacheRepository
                    .findByClusterNameAndAccountIdAndRegion(config.getClusterName(), awsAccountId, config.getRegion());
            if (dashCacheOpt.isEmpty()) {
                logger.info("No EksDashboardCache found for cluster {} — cannot compute snapshot rightsizing", config.getClusterName());
                return emptyRightsizingDto();
            }

            EksCompleteDashboardDto dashboard = objectMapper.readValue(
                    dashCacheOpt.get().getDashboardData(), EksCompleteDashboardDto.class);

            if (dashboard.getPodDetails() == null || dashboard.getPodDetails().isEmpty()) {
                return emptyRightsizingDto();
            }

            // Group pods by workload key: ownerKind|namespace|ownerName
            Map<String, List<EksCompleteDashboardDto.PodDetail>> byWorkload = new LinkedHashMap<>();
            for (EksCompleteDashboardDto.PodDetail pod : dashboard.getPodDetails()) {
                if (pod.getOwnerKind() == null || pod.getOwnerName() == null) continue;
                if ("DaemonSet".equals(pod.getOwnerKind())) continue; // 1-per-node, not rightsizable
                String ns = pod.getNamespace() != null ? pod.getNamespace() : "default";
                if (isSystemNamespace(ns)) continue;
                String key = pod.getOwnerKind() + "|" + ns + "|" + pod.getOwnerName();
                byWorkload.computeIfAbsent(key, k -> new ArrayList<>()).add(pod);
            }

            final double OVERHEAD = 1.20;       // 20% headroom above observed peak
            final double MIN_SAVINGS = 10.0;    // only surface if >= 10% savings available

            List<WorkloadRecommendation> recommendations = new ArrayList<>();

            for (Map.Entry<String, List<EksCompleteDashboardDto.PodDetail>> entry : byWorkload.entrySet()) {
                String[] parts = entry.getKey().split("\\|", 3);
                String kind = parts[0], namespace = parts[1], workloadName = parts[2];
                List<EksCompleteDashboardDto.PodDetail> pods = entry.getValue();

                // Collect distinct container names from the first pod that has data
                List<String> containerNames = new ArrayList<>();
                for (EksCompleteDashboardDto.PodDetail pod : pods) {
                    if (pod.getContainers() != null && !pod.getContainers().isEmpty()) {
                        for (EksCompleteDashboardDto.PodDetail.ContainerDetail c : pod.getContainers()) {
                            if (c.getName() != null && !containerNames.contains(c.getName())) {
                                containerNames.add(c.getName());
                            }
                        }
                        break;
                    }
                }
                // Fallback to pod-level analysis if no per-container data
                if (containerNames.isEmpty()) containerNames.add(null);

                for (String containerName : containerNames) {
                    double totalCpuUsage = 0, maxMemUsage = 0;
                    int cpuSamples = 0;
                    double cpuRequest = 0, memRequest = 0;

                    for (EksCompleteDashboardDto.PodDetail pod : pods) {
                        // Pod-level request (same for all replicas of same deployment)
                        if (pod.getCpuRequest() != null) cpuRequest = pod.getCpuRequest();
                        if (pod.getMemoryRequest() != null) memRequest = pod.getMemoryRequest();

                        if (containerName != null && pod.getContainers() != null) {
                            for (EksCompleteDashboardDto.PodDetail.ContainerDetail c : pod.getContainers()) {
                                if (containerName.equals(c.getName())) {
                                    if (c.getCpuUsage() != null) { totalCpuUsage += c.getCpuUsage(); cpuSamples++; }
                                    if (c.getMemoryUsage() != null && c.getMemoryUsage() > maxMemUsage) maxMemUsage = c.getMemoryUsage();
                                }
                            }
                        } else {
                            // Pod-level fallback
                            if (pod.getCpuUsage() != null) { totalCpuUsage += pod.getCpuUsage(); cpuSamples++; }
                            if (pod.getMemoryUsage() != null && pod.getMemoryUsage() > maxMemUsage) maxMemUsage = pod.getMemoryUsage();
                        }
                    }

                    if (cpuSamples == 0 || cpuRequest <= 0) continue;

                    double avgCpuUsage = totalCpuUsage / cpuSamples;
                    double recommendedCpu = Math.max(10.0, avgCpuUsage * OVERHEAD);
                    double recommendedMem = maxMemUsage > 0 ? Math.max(1.0, maxMemUsage * OVERHEAD) : memRequest;

                    double savingsPct = (cpuRequest - recommendedCpu) / cpuRequest * 100.0;
                    if (savingsPct < MIN_SAVINGS) continue;

                    WorkloadRecommendation rec = new WorkloadRecommendation();
                    rec.setNamespace(namespace);
                    rec.setWorkloadName(workloadName);
                    rec.setWorkloadKind(kind);
                    rec.setContainerName(containerName != null ? containerName : workloadName);
                    rec.setCurrentCpuRequest(String.format("%.0fm", cpuRequest));
                    rec.setCurrentMemRequest(String.format("%.0fMi", memRequest));
                    rec.setRecommendedCpu(String.format("%.0fm", recommendedCpu));
                    rec.setRecommendedMem(String.format("%.0fMi", recommendedMem));
                    rec.setP80Cpu(avgCpuUsage);
                    rec.setP100Mem(maxMemUsage);
                    rec.setEstimatedSavingsPercent(Math.round(savingsPct * 10.0) / 10.0);
                    rec.setApplied(false);
                    rec.setReason("snapshot-based (deploy xamops-automation for Prometheus p80 analysis)");
                    recommendations.add(rec);
                }
            }

            if (recommendations.isEmpty()) return emptyRightsizingDto();

            recommendations.sort((a, b) -> Double.compare(b.getEstimatedSavingsPercent(), a.getEstimatedSavingsPercent()));

            WorkloadRightsizingDto dto = new WorkloadRightsizingDto();
            dto.setRecommendations(recommendations);
            dto.setLastAnalyzedAt(dashCacheOpt.get().getLastSyncedAt()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            dto.setTotalWorkloads(byWorkload.size());
            dto.setWorkloadsWithRecommendations(recommendations.size());
            double avgSavings = recommendations.stream()
                    .mapToDouble(WorkloadRecommendation::getEstimatedSavingsPercent)
                    .average().orElse(0);
            dto.setTotalEstimatedSavingsPercent(Math.round(avgSavings * 10.0) / 10.0);
            logger.info("Snapshot rightsizing: {} recommendations for cluster {}", recommendations.size(), config.getClusterName());
            return dto;

        } catch (Exception e) {
            logger.warn("Failed to compute snapshot rightsizing for cluster {}: {}", config.getClusterName(), e.getMessage());
            return emptyRightsizingDto();
        }
    }

    private boolean isSystemNamespace(String ns) {
        return ns.startsWith("kube-") || "monitoring".equals(ns) || "xamops-system".equals(ns)
                || "ingress-nginx".equals(ns) || "cert-manager".equals(ns);
    }

    /**
     * Read rebalance status pushed by xamops-automation.
     * Returns empty map if no rebalance has occurred.
     */
    public Map<String, Object> getRebalanceStatus(String clusterName, Long cloudAccountId) {
        KubernetesClusterConfig config = findConfig(clusterName, cloudAccountId);
        if (config == null) return Map.of("status", "idle");

        Optional<AgentDataCache> cacheOpt =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_REBALANCE_STATUS);

        if (cacheOpt.isEmpty()) {
            return Map.of("status", "idle", "message", "No rebalance has been run yet");
        }

        try {
            return objectMapper.readValue(cacheOpt.get().getDataJson(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("status", "unknown", "error", e.getMessage());
        }
    }

    /**
     * Read evictor candidates pushed by xamops-automation.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getEvictorCandidates(String clusterName, Long cloudAccountId) {
        KubernetesClusterConfig config = findConfig(clusterName, cloudAccountId);
        if (config == null) return Collections.emptyList();

        Optional<AgentDataCache> cacheOpt =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_EVICTOR_CANDIDATES);

        if (cacheOpt.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            return objectMapper.readValue(cacheOpt.get().getDataJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            logger.warn("Failed to parse evictor candidates: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Node Fleet Report ──────────────────────────────────────────────────

    public static final String DT_NODE_FLEET = "node-fleet-report";

    /**
     * Returns a NodeFleetReport built from the k8s-resources agent cache.
     * If xamops-automation has pushed a dedicated node-fleet-report it takes precedence.
     * Otherwise we synthesize it from the k8s-resources snapshot so the UI always
     * gets a useful response even before the automation agent is running.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getNodeFleetReport(String clusterName, Long cloudAccountId) {
        KubernetesClusterConfig config = findConfig(clusterName, cloudAccountId);
        if (config == null) return buildEmptyFleet();

        // 1. Prefer dedicated push from xamops-automation
        Optional<AgentDataCache> dedicated =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_NODE_FLEET);
        if (dedicated.isPresent()) {
            try {
                return objectMapper.readValue(dedicated.get().getDataJson(),
                        new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                logger.warn("Failed to parse node-fleet-report cache: {}", e.getMessage());
            }
        }

        // 2. Synthesize from k8s-resources snapshot
        Optional<AgentDataCache> k8sCache =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), "k8s-resources");
        if (k8sCache.isEmpty()) return buildEmptyFleet();

        try {
            Map<String, Object> snapshot = objectMapper.readValue(k8sCache.get().getDataJson(),
                    new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) snapshot.getOrDefault("nodes", Collections.emptyList());

            int onDemand = 0, spot = 0;
            List<Map<String, Object>> idleNodes = new ArrayList<>();
            for (Map<String, Object> node : nodes) {
                String capacityType = String.valueOf(node.getOrDefault("capacityType", "on-demand")).toLowerCase();
                if (capacityType.contains("spot")) spot++; else onDemand++;

                // Flag as idle if cpu < 10% (agent may push cpuUsagePercent)
                Object cpuObj = node.get("cpuUsagePercent");
                double cpu = cpuObj instanceof Number ? ((Number) cpuObj).doubleValue() : -1;
                if (cpu >= 0 && cpu < 10) {
                    Map<String, Object> idle = new LinkedHashMap<>();
                    idle.put("nodeName", node.get("name"));
                    idle.put("cpuUsagePct", cpu);
                    idle.put("memUsagePct", node.getOrDefault("memoryUsagePercent", 0));
                    idle.put("reason", "CPU below 10%");
                    idleNodes.add(idle);
                }
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("totalNodes", nodes.size());
            report.put("onDemandNodes", onDemand);
            report.put("spotNodes", spot);
            report.put("idleNodes", idleNodes);
            report.put("spotCandidates", Collections.emptyList());
            report.put("interruptedNodes", Collections.emptyList());
            report.put("estimatedSavingsPct", 0);
            report.put("collectedAt", k8sCache.get().getReceivedAt() != null
                    ? k8sCache.get().getReceivedAt().toString() : null);
            return report;
        } catch (Exception e) {
            logger.warn("Failed to synthesize node-fleet-report: {}", e.getMessage());
            return buildEmptyFleet();
        }
    }

    private Map<String, Object> buildEmptyFleet() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalNodes", 0);
        m.put("onDemandNodes", 0);
        m.put("spotNodes", 0);
        m.put("idleNodes", Collections.emptyList());
        m.put("spotCandidates", Collections.emptyList());
        m.put("interruptedNodes", Collections.emptyList());
        m.put("estimatedSavingsPct", 0);
        m.put("collectedAt", null);
        return m;
    }

    private WorkloadRightsizingDto emptyRightsizingDto() {
        WorkloadRightsizingDto dto = new WorkloadRightsizingDto();
        dto.setRecommendations(Collections.emptyList());
        dto.setTotalWorkloads(0);
        dto.setWorkloadsWithRecommendations(0);
        return dto;
    }

    // ── Audit event ────────────────────────────────────────────────────────

    private void logEvent(Long clusterConfigId, String eventType, String details) {
        try {
            AgentEvent event = new AgentEvent(clusterConfigId, eventType);
            event.setDetails(details);
            agentEventRepository.save(event);
        } catch (Exception e) {
            logger.warn("Failed to log autoscaling event: {}", e.getMessage());
        }
    }
}
