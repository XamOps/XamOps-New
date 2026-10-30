package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.EksDashboardCache;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.AutomationPolicyBundle;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.*;
import com.xammer.cloud.dto.autoscaling.ConsolidationPolicyDto;
import com.xammer.cloud.dto.autoscaling.NodeOptimizerPolicyDto;
import com.xammer.cloud.dto.autoscaling.VpaManagerPolicyDto;
import com.xammer.cloud.dto.autoscaling.VpaRunDto;
import com.xammer.cloud.dto.autoscaling.VpaRunResponse;
import com.xammer.cloud.dto.autoscaling.WorkloadRightsizingDto;
import com.xammer.cloud.dto.autoscaling.WorkloadRightsizingDto.WorkloadRecommendation;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.EksDashboardCacheRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer.Section;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer.UiPolicy;
import com.xammer.cloud.service.spot.ServerTime;
import com.xammer.cloud.service.spot.SpotInterruptionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * EksAutoscalingService — core backend for XamOps Autoscaling Agent.
 *
 * <p>
 * Every method takes the {@link KubernetesClusterConfig} the request guard already resolved
 * ({@code K8sAutomationGuardInterceptor}); nothing here looks a config up by a caller-supplied
 * account id. Policy reads and writes go through {@link K8sAutomationPolicyNormalizer}, so every
 * stored bundle is complete and every served value is safe for the current readiness.
 *
 * Data sources (NO new tables — everything reuses existing infrastructure):
 *
 *   1. kubernetes_cluster_config
 *        - autoscalingEnabled  → node automation on/off
 *        - agent_status        → maps to AgentStatusDto.agentHealthy
 *        - agent_version       → maps to AgentStatusDto.agentVersion
 *        - last_agent_checkin  → maps to AgentStatusDto.lastSyncAt
 *
 *   2. agent_data_cache (existing table, new data_type values)
 *        - dataType = "autoscaling-policy"   → the AutomationPolicyBundle (written by XamOps only;
 *                                              ingest refuses it from agents)
 *        - dataType = "autoscaling-metrics"  → AutoscalingKpisDto (pushed by the agent)
 *        - dataType = "autoscaling-events"   → List<AutoscalingEventDto> (pushed by the agent)
 *        - dataType = "autoscaling-consolidation-plan" → ConsolidationPlanDto (look-only plan pushed
 *                                              by xamops-automation; served by ConsolidationPlanService)
 *
 *   3. agent_events (existing table, event types starting "autoscaling_")
 *        - configuration changes XamOps recorded, served as recentChanges
 */
@Service
public class EksAutoscalingService {

    private static final Logger logger = LoggerFactory.getLogger(EksAutoscalingService.class);

    // ── Data types stored in agent_data_cache ──────────────────────────────
    // Written by XamOps (never by agents):
    public static final String DT_POLICY             = "autoscaling-policy";
    // Pushed by xamops-agent (read-only observer):
    public static final String DT_METRICS            = "autoscaling-metrics";
    public static final String DT_EVENTS             = "autoscaling-events";
    // Pushed by xamops-automation (read-write controller):
    public static final String DT_RIGHTSIZING        = "autoscaling-rightsizing";
    public static final String DT_REBALANCE_STATUS   = "autoscaling-rebalance-status";
    public static final String DT_EVICTOR_CANDIDATES = "autoscaling-evictor-candidates";
    public static final String DT_CONSOLIDATION_PLAN = "autoscaling-consolidation-plan";
    public static final String DT_CONSOLIDATION_RUNS = "autoscaling-consolidation-runs";
    public static final String DT_VPA_RUNS           = "autoscaling-vpa-runs";

    /** Event-type prefix of configuration changes XamOps records. */
    public static final String CHANGE_EVENT_PREFIX = "autoscaling_";

    private static final Map<Section, String> SECTION_LABELS = Map.of(
            Section.SCALE_OUT, "Scale-out policy",
            Section.EVICTOR, "Evictor policy",
            Section.WORKLOAD_RIGHTSIZER, "Workload rightsizer policy",
            Section.SAFETY_GUARDS, "Safety guards",
            Section.NODE_OPTIMIZER, "Node optimizer policy",
            Section.REBALANCER, "Rebalancer config",
            Section.CONSOLIDATION, "Consolidation policy",
            Section.VPA_MANAGER, "VPA manager policy",
            Section.MODE, "Node automation scope");

    /**
     * What capabilities reports per cluster about node automation (design D5).
     *
     * @param derivedState {@code OFF}, {@code ANALYSIS} or {@code AUTOMATED}; capabilities replaces
     *                     it with {@code NOT_INSTALLED} when the automation agent was never seen
     * @param effectiveActing the agent's TRUE effective gate, read from the runtime records it pushes
     *                     (consolidation runs' {@code mode}, the rebalance status' {@code dryRun}) and
     *                     NOT from the stored policy: {@code ACTING}, {@code NOT_ACTING} or
     *                     {@code UNKNOWN} when no runtime signal has been seen
     */
    public record NodeAutomationSummary(boolean enabled, String mode, Boolean evictorAnalyseOnly, String derivedState,
                                        String effectiveActing) {
    }

    /** Tri-state for {@link NodeAutomationSummary#effectiveActing()} (design BUG-1, state-truth). */
    public static final String EFFECTIVE_ACTING = "ACTING";
    public static final String EFFECTIVE_NOT_ACTING = "NOT_ACTING";
    public static final String EFFECTIVE_UNKNOWN = "UNKNOWN";

    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final AgentDataCacheRepository agentDataCacheRepository;
    private final AgentEventRepository agentEventRepository;
    private final EksDashboardCacheRepository eksDashboardCacheRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final K8sAutomationPolicyNormalizer normalizer;
    private final K8sAutomationAccessService accessService;
    private final ObjectMapper objectMapper;
    private final SpotInterruptionProperties spotProperties;
    private final Clock clock;

    @Autowired
    public EksAutoscalingService(
            KubernetesClusterConfigRepository clusterConfigRepository,
            AgentDataCacheRepository agentDataCacheRepository,
            AgentEventRepository agentEventRepository,
            EksDashboardCacheRepository eksDashboardCacheRepository,
            CloudAccountRepository cloudAccountRepository,
            K8sAutomationPolicyNormalizer normalizer,
            K8sAutomationAccessService accessService,
            SpotInterruptionProperties spotProperties) {
        this(clusterConfigRepository, agentDataCacheRepository, agentEventRepository, eksDashboardCacheRepository,
                cloudAccountRepository, normalizer, accessService, spotProperties, Clock.systemUTC());
    }

    /** Test constructor with a fixed clock (the {@code K8sCapabilitiesService} pattern). */
    EksAutoscalingService(
            KubernetesClusterConfigRepository clusterConfigRepository,
            AgentDataCacheRepository agentDataCacheRepository,
            AgentEventRepository agentEventRepository,
            EksDashboardCacheRepository eksDashboardCacheRepository,
            CloudAccountRepository cloudAccountRepository,
            K8sAutomationPolicyNormalizer normalizer,
            K8sAutomationAccessService accessService,
            SpotInterruptionProperties spotProperties,
            Clock clock) {
        this.spotProperties = spotProperties;
        this.clock = clock;
        this.clusterConfigRepository = clusterConfigRepository;
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.agentEventRepository = agentEventRepository;
        this.eksDashboardCacheRepository = eksDashboardCacheRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.normalizer = normalizer;
        this.accessService = accessService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        // An added key in an agent payload must not blank the feed or the KPI tiles (E21, R6).
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The full dashboard for a cluster, whether or not node automation is enabled (E19): every
     * policy section with its effective values, {@code policySource}, {@code agentPolicyBlockedBy},
     * the agent's own events and the configuration changes XamOps recorded.
     */
    public AutoscalerDashboardDto getDashboard(KubernetesClusterConfig config) {
        boolean enabled = Boolean.TRUE.equals(config.getAutoscalingEnabled());
        K8sAutomationReason block = accessService.agentPolicyBlock(config);
        UiPolicy policy = normalizer.forUi(storedPolicyJson(config.getId()), config.getId(), block);
        AutomationPolicyBundle bundle = policy.bundle();

        AutoscalerDashboardDto dto = new AutoscalerDashboardDto();
        dto.setAutoscalingEnabled(enabled);
        dto.setPolicySource(policy.policySource());
        dto.setAgentPolicyBlockedBy(block != null ? block.name() : null);
        AgentStatusDto agentStatus = enabled ? buildAgentStatus(config, bundle) : disabledAgentStatus(config);
        // Actuator state is its own axis, derived from the actuator flag — never agent liveness.
        agentStatus.setActuatorState(enabled ? "ACTIVE" : "DISABLED");
        dto.setAgentStatus(agentStatus);
        dto.setScaleOutPolicy(bundle.getScaleOutPolicy());
        dto.setEvictorPolicy(bundle.getEvictorPolicy());
        dto.setWorkloadRightsizerPolicy(bundle.getWorkloadRightsizerPolicy());
        dto.setHpaManagerPolicy(bundle.getHpaManagerPolicy());
        dto.setVpaManagerPolicy(bundle.getVpaManagerPolicy());
        dto.setNodeTemplates(bundle.getNodeTemplates());
        dto.setRebalancer(bundle.getRebalancer());
        dto.setSafetyGuards(bundle.getSafetyGuards());
        dto.setNodeOptimizerPolicy(bundle.getNodeOptimizerPolicy());
        dto.setKpis(readMetrics(config.getId()));
        dto.setRecentEvents(readEvents(config.getId()));
        dto.setRecentChanges(readChanges(config.getId()));
        return dto;
    }

    /** Node automation state for capabilities (design D5). */
    public NodeAutomationSummary summarize(KubernetesClusterConfig config) {
        boolean enabled = Boolean.TRUE.equals(config.getAutoscalingEnabled());
        UiPolicy policy = normalizer.forUi(storedPolicyJson(config.getId()), config.getId(), null);
        AutomationPolicyBundle b = policy.bundle();
        boolean stored = "stored".equals(policy.policySource());
        String derived;
        if (!enabled || "disabled".equals(b.getAutoscalerMode())) {
            derived = "OFF";
        } else if (!b.getEvictorPolicy().isAnalyseOnly()
                || b.getNodeOptimizerPolicy().isAutoCordonIdleNodes()
                || b.getNodeOptimizerPolicy().isAutoDrainIdleNodes()
                || b.getNodeOptimizerPolicy().getSpotRatioTargetPct() != 0
                || b.getRebalancer().isEnabled()
                || b.getRebalancer().isScheduleEnabled()
                || b.getConsolidationPolicy().isEnabled()) {
            derived = "AUTOMATED";
        } else {
            derived = "ANALYSIS";
        }
        return new NodeAutomationSummary(enabled,
                stored || enabled ? b.getAutoscalerMode() : null,
                stored || enabled ? b.getEvictorPolicy().isAnalyseOnly() : null,
                derived,
                effectiveActing(config));
    }

    /**
     * The agent's TRUE effective node-automation gate, read ONLY from the runtime records it pushes
     * (never the stored policy — that is exactly the signal BUG-1 can't trust). A controller running
     * live stamps {@code mode=live}/{@code dryRun=false} on its newest record; a controller turned
     * off in this environment (DRY_RUN / ENABLE_*=false) stamps {@code mode=simulate}/{@code dryRun=true}.
     *
     * <ul>
     *   <li>{@code ACTING} — any fresh runtime record shows a live, non-dry-run controller;</li>
     *   <li>{@code NOT_ACTING} — a fresh runtime record exists and every one is simulate / dry-run;</li>
     *   <li>{@code UNKNOWN} — no runtime record has been seen (older agent, nothing pushed, or a read
     *       failure), so we honestly can't tell. Never fabricated.</li>
     * </ul>
     */
    private String effectiveActing(KubernetesClusterConfig config) {
        boolean sawSignal = false;

        Boolean consolidation = consolidationRunsActing(config);
        if (consolidation != null) {
            if (Boolean.TRUE.equals(consolidation)) {
                return EFFECTIVE_ACTING;
            }
            sawSignal = true;
        }

        Boolean rebalance = rebalanceActing(config);
        if (rebalance != null) {
            if (Boolean.TRUE.equals(rebalance)) {
                return EFFECTIVE_ACTING;
            }
            sawSignal = true;
        }

        return sawSignal ? EFFECTIVE_NOT_ACTING : EFFECTIVE_UNKNOWN;
    }

    /**
     * The newest consolidation run's gate: {@code true} when it ran {@code mode=live} (acting),
     * {@code false} when it ran {@code mode=simulate} / {@code dryRun=true} (not acting), {@code null}
     * when no readable run exists.
     */
    private Boolean consolidationRunsActing(KubernetesClusterConfig config) {
        if (config == null) {
            return null;
        }
        Optional<AgentDataCache> cacheOpt =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_CONSOLIDATION_RUNS);
        if (cacheOpt.isEmpty() || cacheOpt.get().getDataJson() == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(cacheOpt.get().getDataJson());
            JsonNode runs = root.path("runs");
            if (!runs.isArray() || runs.isEmpty()) {
                return null;
            }
            // Runs are newest-first; the first readable run's gate is the current effective one.
            JsonNode newest = runs.get(0);
            if (newest.hasNonNull("dryRun")) {
                return !newest.get("dryRun").asBoolean();
            }
            if (newest.hasNonNull("mode")) {
                return "live".equalsIgnoreCase(newest.get("mode").asText());
            }
            return null;
        } catch (Exception e) {
            logger.warn("Consolidation runs for cluster config {} couldn't be read for the effective gate: {}",
                    config.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * The rebalance status' gate: {@code true} when the controller reported it is acting
     * ({@code dryRun=false}), {@code false} when {@code dryRun=true}, {@code null} when the record
     * carries no {@code dryRun} (nothing has run, or an older agent didn't stamp it).
     */
    private Boolean rebalanceActing(KubernetesClusterConfig config) {
        if (config == null) {
            return null;
        }
        Optional<AgentDataCache> cacheOpt =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_REBALANCE_STATUS);
        if (cacheOpt.isEmpty() || cacheOpt.get().getDataJson() == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(cacheOpt.get().getDataJson());
            if (root.hasNonNull("dryRun")) {
                return !root.get("dryRun").asBoolean();
            }
            return null;
        } catch (Exception e) {
            logger.warn("Rebalance status for cluster config {} couldn't be read for the effective gate: {}",
                    config.getId(), e.getMessage());
            return null;
        }
    }

    // ── Policy saves ──────────────────────────────────────────────────────

    @Transactional
    public void saveScaleOutPolicy(KubernetesClusterConfig config, ScaleOutPolicyDto policy, String username) {
        saveSection(config, Section.SCALE_OUT, policy, username);
    }

    @Transactional
    public void saveEvictorPolicy(KubernetesClusterConfig config, EvictorPolicyDto policy, String username) {
        saveSection(config, Section.EVICTOR, policy, username);
    }

    @Transactional
    public void saveWorkloadRightsizerPolicy(KubernetesClusterConfig config, WorkloadRightsizerPolicyDto policy,
                                             String username) {
        // Always refused: the Go rightsizer is retired (design §6.6).
        saveSection(config, Section.WORKLOAD_RIGHTSIZER, policy, username);
    }

    @Transactional
    public void saveSafetyGuards(KubernetesClusterConfig config, SafetyGuardsDto guards, String username) {
        saveSection(config, Section.SAFETY_GUARDS, guards, username);
    }

    @Transactional
    public void saveNodeOptimizerPolicy(KubernetesClusterConfig config, NodeOptimizerPolicyDto policy, String username) {
        saveSection(config, Section.NODE_OPTIMIZER, policy, username);
    }

    @Transactional
    public void saveRebalancerConfig(KubernetesClusterConfig config, RebalancerConfigDto rebalancer, String username) {
        saveSection(config, Section.REBALANCER, rebalancer, username);
    }

    @Transactional
    public void saveConsolidationPolicy(KubernetesClusterConfig config, ConsolidationPolicyDto policy, String username) {
        // enabled=true is refused by validateConsolidation unless the consolidation-actions readiness
        // key is ready; the coercion table still serves enabled=false to the agent until then.
        saveSection(config, Section.CONSOLIDATION, policy, username);
    }

    @Transactional
    public void saveVpaManagerPolicy(KubernetesClusterConfig config, VpaManagerPolicyDto policy, String username) {
        // An acting policy (enabled && !analyseOnly && acting updateMode) is refused by
        // validateVpaManager unless the vpa-apply readiness key is ready; even when stored, the
        // coercion table still serves it inert (enabled=false, analyseOnly=true, updateMode=Off)
        // until the key flips.
        saveSection(config, Section.VPA_MANAGER, policy, username);
    }

    /**
     * The VPA manager's stored run history (the LIST collector {@link #DT_VPA_RUNS}), newest first.
     * Honest empty state: no cache is {@code NO_RUNS}, an unparseable payload is {@code UNREADABLE};
     * nothing is fabricated. Mirrors the {@code DT_CONSOLIDATION_RUNS} read path.
     */
    public VpaRunResponse readVpaRuns(KubernetesClusterConfig config) {
        if (config == null) {
            return VpaRunResponse.noRuns();
        }
        Optional<AgentDataCache> cacheOpt =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_VPA_RUNS);
        if (cacheOpt.isEmpty() || cacheOpt.get().getDataJson() == null) {
            return VpaRunResponse.noRuns();
        }
        List<VpaRunDto> runs;
        try {
            runs = objectMapper.readValue(cacheOpt.get().getDataJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, VpaRunDto.class));
        } catch (Exception e) {
            // The message is logged, never the stored JSON, and nothing of it is returned.
            logger.warn("VPA runs for cluster config {} couldn't be parsed: {}", config.getId(), e.getMessage());
            return VpaRunResponse.unreadable();
        }
        if (runs == null) {
            return VpaRunResponse.unreadable();
        }
        List<VpaRunDto> history = new ArrayList<>(runs);
        java.util.Collections.reverse(history); // the agent appends oldest-first; serve newest-first
        return VpaRunResponse.ok(history);
    }

    @Transactional
    public void setMode(KubernetesClusterConfig config, String mode, String username) {
        normalizer.validate(Section.MODE, mode);
        AutomationPolicyBundle next = normalizer.replaceSection(readForWrite(config), Section.MODE, mode);
        writePolicyBundle(config.getId(), next);
        logEvent(config.getId(), "autoscaling_config_change",
                "Node automation scope changed to " + mode + " by " + username);
    }

    @Transactional
    public void saveNodeTemplate(KubernetesClusterConfig config, String templateId, NodeTemplateDto template,
                                 String username) {
        normalizer.validateNodeTemplate(templateId);
        writePolicyBundle(config.getId(), normalizer.upsertNodeTemplate(readForWrite(config), templateId, template));
        logEvent(config.getId(), "autoscaling_config_change", "Node template " + templateId + " saved by " + username);
    }

    @Transactional
    public void deleteNodeTemplate(KubernetesClusterConfig config, String templateId, String username) {
        normalizer.validateNodeTemplate(templateId);
        writePolicyBundle(config.getId(), normalizer.deleteNodeTemplate(readForWrite(config), templateId));
        logEvent(config.getId(), "autoscaling_config_change", "Node template " + templateId + " deleted by " + username);
    }

    /**
     * Trigger a manual rebalance. Refused while the rebalancer isn't ready (D7); once it is, the
     * stored rebalancer status becomes "running" and the agent picks it up on its next poll.
     */
    @Transactional
    public void triggerRebalance(KubernetesClusterConfig config, String username) {
        normalizer.validateRebalanceTrigger();
        AutomationPolicyBundle bundle = readForWrite(config);
        bundle.getRebalancer().setStatus("running");
        writePolicyBundle(config.getId(), bundle);
        logEvent(config.getId(), "autoscaling_rebalance_triggered", "Manual rebalance triggered by " + username);
        logger.info("Rebalance triggered for cluster: {} (configId: {})", config.getClusterName(), config.getId());
    }

    /**
     * Turn node automation on or off. The first enable persists the complete, analysis-only default
     * bundle so the agent's first fetch is complete (design §6.6). Both directions are recorded in
     * Activity (NIT 7).
     */
    @Transactional
    public void setAutoscalingEnabled(KubernetesClusterConfig config, boolean enabled, String username) {
        config.setAutoscalingEnabled(enabled);
        clusterConfigRepository.save(config);
        if (enabled && storedPolicyJson(config.getId()) == null) {
            writePolicyBundle(config.getId(), normalizer.defaults());
        }
        logEvent(config.getId(), "autoscaling_config_change",
                "Node automation turned " + (enabled ? "on" : "off") + " by " + username);
        logger.info("Autoscaling {} for cluster: {} (configId: {})",
                enabled ? "ENABLED" : "DISABLED", config.getClusterName(), config.getId());
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

    /**
     * One section save: read the stored bundle (complete and safe), validate, replace the whole
     * section (server-owned fields kept), normalize and persist a complete bundle.
     */
    private void saveSection(KubernetesClusterConfig config, Section section, Object body, String username) {
        normalizer.validate(section, body);
        AutomationPolicyBundle next = normalizer.replaceSection(readForWrite(config), section, body);
        writePolicyBundle(config.getId(), next);
        logEvent(config.getId(), "autoscaling_config_change", SECTION_LABELS.get(section) + " updated by " + username);
    }

    // ── Agent status mapping ───────────────────────────────────────────────

    private AgentStatusDto disabledAgentStatus(KubernetesClusterConfig config) {
        AgentStatusDto status = new AgentStatusDto();
        status.setMode("disabled");
        // Agent liveness is independent of the actuator being disabled: reuse the vertical read's
        // predicate (config.isAgentActive()) so a live agent on an analysis-only cluster reads healthy.
        status.setAgentHealthy(config.isAgentActive());
        status.setClusterControllerHealthy(false);
        status.setEvictorHealthy(false);
        status.setWorkloadAutoscalerHealthy(false);
        status.setAgentVersion(config.getAgentVersion() != null ? config.getAgentVersion() : "not-installed");
        return status;
    }

    private AgentStatusDto buildAgentStatus(KubernetesClusterConfig config, AutomationPolicyBundle bundle) {
        AgentStatusDto dto = new AgentStatusDto();

        boolean agentActive = config.isAgentActive();
        // Agent liveness = config.isAgentActive() alone — byte-for-byte the vertical read's agentPath
        // predicate — so the node dashboard and the vertical page never disagree about the same agent.
        dto.setAgentHealthy(agentActive);
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

        // Mode from the effective policy bundle
        dto.setMode(bundle.getAutoscalerMode());

        return dto;
    }

    // ── Policy bundle (all policies stored as a single JSON object) ────────

    private String storedPolicyJson(Long clusterConfigId) {
        return agentDataCacheRepository.findByClusterConfigIdAndDataType(clusterConfigId, DT_POLICY)
                .map(AgentDataCache::getDataJson)
                .orElse(null);
    }

    private AutomationPolicyBundle readForWrite(KubernetesClusterConfig config) {
        return normalizer.readForWrite(storedPolicyJson(config.getId()), config.getId());
    }

    /** Persists the bundle normalized: every stored bundle is complete and safe. */
    private void writePolicyBundle(Long clusterConfigId, AutomationPolicyBundle bundle) {
        String json = normalizer.toJson(normalizer.normalize(bundle, clusterConfigId));
        upsertAgentDataCache(clusterConfigId, DT_POLICY, json, 1, null);
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

    /** The automation agent's own events: its autoscaling-events row only, newest 20. */
    private List<AutoscalingEventDto> readEvents(Long clusterConfigId) {
        Optional<AgentDataCache> cacheOpt =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(clusterConfigId, DT_EVENTS);
        if (cacheOpt.isEmpty() || cacheOpt.get().getDataJson() == null) {
            return Collections.emptyList();
        }
        try {
            List<AutoscalingEventDto> events = objectMapper.readValue(
                    cacheOpt.get().getDataJson(),
                    new TypeReference<List<AutoscalingEventDto>>() {});
            if (events == null) return Collections.emptyList();
            return events.size() > 20 ? events.subList(0, 20) : events;
        } catch (Exception e) {
            logger.warn("Failed to parse autoscaling events for configId={}: {}", clusterConfigId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Configuration changes XamOps recorded, newest 20, with ISO-8601 UTC timestamps. */
    private List<AutomationChangeDto> readChanges(Long clusterConfigId) {
        try {
            return agentEventRepository
                    .findTop20ByClusterConfigIdAndEventTypeStartingWithOrderByCreatedAtDesc(clusterConfigId,
                            CHANGE_EVENT_PREFIX)
                    .stream()
                    .map(e -> new AutomationChangeDto(String.valueOf(e.getId()), toUtc(e.getCreatedAt()),
                            e.getEventType(), e.getDetails() != null ? e.getDetails() : e.getEventType()))
                    .toList();
        } catch (Exception e) {
            logger.warn("Failed to read automation changes for configId={}: {}", clusterConfigId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** agent_events.created_at is written with LocalDateTime.now(), so it is in the JVM's zone. */
    static String toUtc(LocalDateTime serverTime) {
        return serverTime == null ? null : serverTime.atZone(ZoneId.systemDefault()).toInstant().toString();
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
    public WorkloadRightsizingDto getRightsizingData(KubernetesClusterConfig config) {
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
        logger.info("No autoscaler rightsizing data for cluster {} — falling back to snapshot analysis from EksDashboardCache", config.getClusterName());
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
     * Returns {"status": "idle"} with a message if no rebalance has run, and {"status": "unknown"}
     * when the stored status can't be parsed (the parse message is logged, never returned: it can
     * quote the stored JSON).
     */
    public Map<String, Object> getRebalanceStatus(KubernetesClusterConfig config) {
        if (config == null) return Map.of("status", "idle");

        Optional<AgentDataCache> cacheOpt =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), DT_REBALANCE_STATUS);

        if (cacheOpt.isEmpty()) {
            return Map.of("status", "idle", "message", "No rebalance has been run yet");
        }

        try {
            Map<String, Object> status = objectMapper.readValue(cacheOpt.get().getDataJson(),
                    new TypeReference<Map<String, Object>>() {});
            return status != null ? status : Map.of("status", "unknown");
        } catch (Exception e) {
            logger.warn("Rebalance status for cluster config {} couldn't be parsed: {}", config.getId(), e.getMessage());
            return Map.of("status", "unknown");
        }
    }

    /**
     * Read evictor candidates pushed by xamops-automation.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getEvictorCandidates(KubernetesClusterConfig config) {
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

    /** Legacy dedicated fleet push; nothing sends it today, kept for compatibility (design §B5.1). */
    public static final String DT_NODE_FLEET = "node-fleet-report";
    /** The autoscaler's NodeOptimizer report (a single-object collector: the report itself). */
    public static final String DT_NODE_OPTIMIZER_REPORT = "node-optimizer-report";

    /** {@code interruptedNodesReason} when a report row exists but is older than the max age. */
    static final String REPORT_STALE = "stale";
    /** {@code interruptedNodesReason} when no report row exists (or it does not parse). */
    static final String REPORT_NOT_REPORTED = "not-reported";

    private static final List<String> REPORT_LISTS =
            List.of("interruptedNodes", "interruptionNotices", "idleNodes", "spotCandidates");

    /**
     * The node fleet for the Node autoscaler page and the Overview (design §B5.1), serving only
     * what a producer observed:
     * <ol>
     *   <li>the autoscaler's {@code node-optimizer-report} while it is fresh, normalized by
     *       {@link #normalizeReport};</li>
     *   <li>the legacy {@code node-fleet-report} while it is fresh, normalized the same way;</li>
     *   <li>node counts synthesized from the {@code k8s-resources} snapshot, with every list the
     *       snapshot does not carry served as {@code null} and {@code interruptedNodesReported=false};</li>
     *   <li>an empty fleet ({@code collectedAt: null}) with the same nulls and flags.</li>
     * </ol>
     * Freshness compares Instants ({@link ServerTime#fresh}); every served time ends in {@code Z}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getNodeFleetReport(KubernetesClusterConfig config) {
        if (config == null) return buildEmptyFleet(false, null);

        // 1-2. A fresh autoscaler report wins; the legacy dedicated push is the fallback.
        boolean staleReport = false;
        Instant staleReportAt = null;
        for (String dataType : List.of(DT_NODE_OPTIMIZER_REPORT, DT_NODE_FLEET)) {
            Optional<AgentDataCache> row =
                    agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), dataType);
            if (row.isEmpty()) continue;
            if (!ServerTime.fresh(row.get().getReceivedAt(), clock, spotProperties.reportMaxAge())) {
                Instant at = ServerTime.serverInstant(row.get().getReceivedAt());
                if (!staleReport || (at != null && (staleReportAt == null || at.isAfter(staleReportAt)))) {
                    staleReportAt = at;
                }
                staleReport = true;
                continue;
            }
            try {
                Map<String, Object> report = objectMapper.readValue(row.get().getDataJson(),
                        new TypeReference<LinkedHashMap<String, Object>>() {});
                if (report != null) {
                    normalizeReport(report);
                    return report;
                }
            } catch (Exception e) {
                logger.warn("Failed to parse {} cache: {}", dataType, e.getMessage());
            }
        }

        // 3. Synthesize node counts from the k8s-resources snapshot.
        Optional<AgentDataCache> k8sCache =
                agentDataCacheRepository.findByClusterConfigIdAndDataType(config.getId(), "k8s-resources");
        if (k8sCache.isEmpty()) return buildEmptyFleet(staleReport, staleReportAt);

        try {
            Map<String, Object> snapshot = objectMapper.readValue(k8sCache.get().getDataJson(),
                    new TypeReference<Map<String, Object>>() {});
            Object nodesObj = snapshot == null ? null : snapshot.get("nodes");
            List<Map<String, Object>> nodes = nodesObj instanceof List<?> list
                    ? (List<Map<String, Object>>) list : Collections.emptyList();

            int onDemand = 0, spot = 0;
            boolean sawCpu = false;
            List<Map<String, Object>> idleNodes = new ArrayList<>();
            for (Map<String, Object> node : nodes) {
                String capacityType = String.valueOf(node.getOrDefault("capacityType", "on-demand")).toLowerCase();
                if (capacityType.contains("spot")) spot++; else onDemand++;

                // Idle only when the agent reported usage (NodeDetail carries none today).
                Object cpuObj = node.get("cpuUsagePercent");
                if (!(cpuObj instanceof Number)) continue;
                sawCpu = true;
                double cpu = ((Number) cpuObj).doubleValue();
                if (cpu >= 0 && cpu < 10) {
                    Map<String, Object> idle = new LinkedHashMap<>();
                    idle.put("nodeName", node.get("name"));
                    idle.put("cpuUsagePct", cpu);
                    idle.put("memUsagePct", node.get("memoryUsagePercent"));
                    idle.put("reason", "CPU below 10%");
                    idleNodes.add(idle);
                }
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("totalNodes", nodes.size());
            report.put("onDemandNodes", onDemand);
            report.put("spotNodes", spot);
            report.put("idleNodes", sawCpu ? idleNodes : null);
            putUnobserved(report, staleReport, staleReportAt);
            Instant receivedAt = ServerTime.serverInstant(k8sCache.get().getReceivedAt());
            report.put("collectedAt", receivedAt != null ? receivedAt.toString() : null);
            return report;
        } catch (Exception e) {
            logger.warn("Failed to synthesize node-fleet-report: {}", e.getMessage());
            return buildEmptyFleet(staleReport, staleReportAt);
        }
    }

    /**
     * Normalizes a fresh report (design §B5.1). Whether {@code interruptionNotices} was reported is
     * decided BEFORE the lists are normalized: an absent or null key means an older agent.
     */
    static void normalizeReport(Map<String, Object> report) {
        // Decide "reported" BEFORE normalizing: an absent or null key means an older agent that doesn't send it.
        boolean noticesReported = report.get("interruptionNotices") != null;
        // A fresh report is a completed Analyse run, so a missing/null list (Go nil slice → JSON null) means "none found".
        // Map.putIfAbsent replaces both a missing key and a key mapped to null.
        for (String k : REPORT_LISTS) report.putIfAbsent(k, List.of());
        report.put("interruptionNoticesReported", noticesReported);
        report.put("interruptedNodesReported", true);
    }

    /**
     * What branches 3-4 did not observe: null lists and the not-reported flags with their reason.
     * The caller sets {@code idleNodes}.
     */
    private static void putUnobserved(Map<String, Object> m, boolean staleReport, Instant staleReportAt) {
        m.put("spotCandidates", null);
        m.put("interruptedNodes", null);
        m.put("interruptionNotices", null);
        m.put("estimatedSavingsPct", null);
        m.put("interruptedNodesReported", false);
        m.put("interruptionNoticesReported", false);
        if (staleReport) {
            m.put("interruptedNodesReason", REPORT_STALE);
            m.put("interruptedNodesReportAt", staleReportAt != null ? staleReportAt.toString() : null);
        } else {
            m.put("interruptedNodesReason", REPORT_NOT_REPORTED);
        }
    }

    private Map<String, Object> buildEmptyFleet(boolean staleReport, Instant staleReportAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalNodes", 0);
        m.put("onDemandNodes", 0);
        m.put("spotNodes", 0);
        m.put("idleNodes", null);
        putUnobserved(m, staleReport, staleReportAt);
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
