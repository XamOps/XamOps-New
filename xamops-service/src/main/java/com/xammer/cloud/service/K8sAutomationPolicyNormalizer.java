package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.config.K8sReadinessKey;
import com.xammer.cloud.dto.autoscaling.AutomationPolicyBundle;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.EvictorPolicyDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.NodeTemplateDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.RebalancerConfigDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.SafetyGuardsDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.ScaleOutPolicyDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.WorkloadRightsizerPolicyDto;
import com.xammer.cloud.dto.autoscaling.HpaManagerPolicyDto;
import com.xammer.cloud.dto.autoscaling.NodeOptimizerPolicyDto;
import com.xammer.cloud.dto.autoscaling.VpaManagerPolicyDto;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Owns the shape and the safety of the automation policy bundle (design §6.6, §6.7, §13, NIT 11).
 *
 * <ul>
 *   <li><b>Serve paths.</b> {@link #forAgent} (what the automation agent receives) and
 *       {@link #forUi} (what {@code GET /api/eks/autoscaling/{c}} shows) both return a complete
 *       bundle with the §6.7 coercion table applied: whatever is stored, a controller that isn't
 *       ready is served its safe value. The table, not the write path, is the kill switch, because
 *       several sections have no write endpoint and a stored row can predate this class.</li>
 *   <li><b>Write path.</b> {@link #readForWrite}, {@link #validate} (readiness refusals first, then
 *       locked guardrails, then ranges), {@link #replaceSection} (the UI always sends a complete
 *       section; a {@code null} {@code criticalNamespaces} keeps the stored list; rebalancer
 *       server-owned fields are kept), then {@link #normalize} and persist.</li>
 * </ul>
 *
 * <p>
 * The Go rightsizer is retired: served disabled whatever the readiness, and its write endpoint
 * is always refused. {@code kube-system} and {@code xamops-system} are always protected. Coercion
 * and parse-failure logs are deduplicated per (config id, field set) in a concurrent set capped
 * at {@value #LOG_DEDUP_CAP} entries, so a 60 s agent poll doesn't repeat them.
 */
@Service
public class K8sAutomationPolicyNormalizer {

    private static final Logger log = LoggerFactory.getLogger(K8sAutomationPolicyNormalizer.class);

    /** Union of the Java and Go defaults. */
    public static final List<String> DEFAULT_CRITICAL_NAMESPACES =
            List.of("kube-system", "monitoring", "ingress-nginx", "xamops-system");

    /** Always protected, added silently when missing (tightening). */
    public static final List<String> REQUIRED_CRITICAL_NAMESPACES = List.of("kube-system", "xamops-system");

    public static final Set<String> MODES = Set.of("full", "scoped", "disabled");

    public static final String RETIRED_RIGHTSIZER_MESSAGE = "The automation agent's rightsizer is retired. "
            + "Workload rightsizing is configured in Workload autoscaling.";

    static final int LOG_DEDUP_CAP = 1_000;
    static final int MAX_CRITICAL_NAMESPACES = 50;

    private static final Pattern DNS_1123 = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");
    private static final Pattern TEMPLATE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** The section a write endpoint replaces. */
    public enum Section {
        SCALE_OUT, EVICTOR, WORKLOAD_RIGHTSIZER, SAFETY_GUARDS, NODE_OPTIMIZER, REBALANCER, MODE
    }

    /**
     * What the UI is served.
     *
     * @param policySource         {@code "stored"} when a saved bundle exists, {@code "default"} when
     *                             none does (or it couldn't be read)
     * @param agentPolicyBlockedBy {@code READ_ONLY_CONNECTION} or {@code CONNECTION_NOT_VERIFIED} when
     *                             the owning account keeps the agent's policy disabled (NIT 11), else null
     */
    public record UiPolicy(AutomationPolicyBundle bundle, String policySource, K8sAutomationReason agentPolicyBlockedBy) {
    }

    /** One row of the §6.7 coercion table. {@code key == null} means always (retired or locked). */
    record Coercion(K8sReadinessKey key, String fields, Predicate<AutomationPolicyBundle> violated,
                    Consumer<AutomationPolicyBundle> apply) {
    }

    private final K8sAutomationReadinessProperties readiness;
    private final ObjectMapper mapper;
    private final Set<String> loggedOnce = ConcurrentHashMap.newKeySet();
    final List<Coercion> coercions;

    public K8sAutomationPolicyNormalizer(K8sAutomationReadinessProperties readiness) {
        this.readiness = readiness;
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.coercions = List.of(
                new Coercion(null, "workloadRightsizerPolicy.enabled,workloadRightsizerPolicy.applyMode",
                        b -> b.getWorkloadRightsizerPolicy().isEnabled()
                                || !"deferred".equals(b.getWorkloadRightsizerPolicy().getApplyMode()),
                        b -> {
                            b.getWorkloadRightsizerPolicy().setEnabled(false);
                            b.getWorkloadRightsizerPolicy().setApplyMode("deferred");
                        }),
                new Coercion(K8sReadinessKey.EVICTOR_LIVE, "evictorPolicy.analyseOnly",
                        b -> !b.getEvictorPolicy().isAnalyseOnly(),
                        b -> b.getEvictorPolicy().setAnalyseOnly(true)),
                new Coercion(K8sReadinessKey.HPA_APPLY, "hpaManagerPolicy.analyseOnly",
                        b -> !b.getHpaManagerPolicy().isAnalyseOnly(),
                        b -> b.getHpaManagerPolicy().setAnalyseOnly(true)),
                new Coercion(K8sReadinessKey.VPA_APPLY, "vpaManagerPolicy.analyseOnly,vpaManagerPolicy.updateMode",
                        b -> !b.getVpaManagerPolicy().isAnalyseOnly() || !"Off".equals(b.getVpaManagerPolicy().getUpdateMode()),
                        b -> {
                            b.getVpaManagerPolicy().setAnalyseOnly(true);
                            b.getVpaManagerPolicy().setUpdateMode("Off");
                        }),
                new Coercion(K8sReadinessKey.NODE_OPTIMIZER_ACTIONS,
                        "nodeOptimizerPolicy.autoCordonIdleNodes,nodeOptimizerPolicy.autoDrainIdleNodes,"
                                + "nodeOptimizerPolicy.spotRatioTargetPct",
                        b -> b.getNodeOptimizerPolicy().isAutoCordonIdleNodes()
                                || b.getNodeOptimizerPolicy().isAutoDrainIdleNodes()
                                || b.getNodeOptimizerPolicy().getSpotRatioTargetPct() != 0,
                        b -> {
                            b.getNodeOptimizerPolicy().setAutoCordonIdleNodes(false);
                            b.getNodeOptimizerPolicy().setAutoDrainIdleNodes(false);
                            b.getNodeOptimizerPolicy().setSpotRatioTargetPct(0);
                        }),
                new Coercion(K8sReadinessKey.REBALANCER, "rebalancer.status,rebalancer.scheduleEnabled,rebalancer.enabled",
                        b -> !"idle".equals(b.getRebalancer().getStatus()) || b.getRebalancer().isScheduleEnabled()
                                || b.getRebalancer().isEnabled(),
                        b -> {
                            b.getRebalancer().setStatus("idle");
                            b.getRebalancer().setScheduleEnabled(false);
                            b.getRebalancer().setEnabled(false);
                        }),
                new Coercion(null, "nodeOptimizerPolicy.maxParallelDrains,safetyGuards.respectPodDisruptionBudgets,"
                        + "safetyGuards.protectCriticalNamespaces,safetyGuards.criticalNamespaces",
                        b -> b.getNodeOptimizerPolicy().getMaxParallelDrains() != 1
                                || !b.getSafetyGuards().isRespectPodDisruptionBudgets()
                                || !b.getSafetyGuards().isProtectCriticalNamespaces()
                                || !b.getSafetyGuards().getCriticalNamespaces().containsAll(REQUIRED_CRITICAL_NAMESPACES),
                        b -> {
                            b.getNodeOptimizerPolicy().setMaxParallelDrains(1);
                            b.getSafetyGuards().setRespectPodDisruptionBudgets(true);
                            b.getSafetyGuards().setProtectCriticalNamespaces(true);
                            b.getSafetyGuards().setCriticalNamespaces(withRequired(b.getSafetyGuards().getCriticalNamespaces()));
                        }));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SERVE PATHS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The bundle the automation agent receives. When the owning account isn't read-write and
     * {@code CONNECTED}, the complete default with {@code autoscalerMode: "disabled"} (NIT 11); an
     * unparseable stored bundle is served the same way, so the agent idles.
     */
    public AutomationPolicyBundle forAgent(String storedJson, Long configId, boolean accountAllowsAutomation) {
        if (!accountAllowsAutomation) {
            logOnce("owner|" + configId, () -> log.warn(
                    "Serving a disabled automation policy for cluster config {}: the owning account isn't "
                            + "read-write and connected", configId));
            return disabledDefault();
        }
        return read(storedJson, configId).bundle();
    }

    /** What {@code GET /api/eks/autoscaling/{c}} shows: the effective policy, its source, and the agent block. */
    public UiPolicy forUi(String storedJson, Long configId, K8sAutomationReason ownerBlock) {
        Read r = read(storedJson, configId);
        return new UiPolicy(r.bundle(), r.stored() ? "stored" : "default", ownerBlock);
    }

    /** The stored bundle made complete and safe, as the write path's starting point. */
    public AutomationPolicyBundle readForWrite(String storedJson, Long configId) {
        return read(storedJson, configId).bundle();
    }

    /** The complete default bundle: mode {@code full}, analysis only, rebalancer idle and off. */
    public AutomationPolicyBundle defaults() {
        AutomationPolicyBundle b = new AutomationPolicyBundle();
        b.setAutoscalerMode("full");
        fillMissing(b);
        return b;
    }

    /** Fills every missing section and applies the coercion table; logs coerced fields once per set. */
    public AutomationPolicyBundle normalize(AutomationPolicyBundle bundle, Long configId) {
        AutomationPolicyBundle b = copy(bundle);
        List<String> coercedFields = new ArrayList<>();
        if (b.getAutoscalerMode() != null && !MODES.contains(b.getAutoscalerMode())) {
            b.setAutoscalerMode("disabled");
            coercedFields.add("autoscalerMode");
        }
        fillMissing(b);
        for (Coercion c : coercions) {
            if ((c.key() == null || !readiness.isReady(c.key())) && c.violated().test(b)) {
                c.apply().accept(b);
                coercedFields.add(c.fields());
            }
        }
        if (!coercedFields.isEmpty() && configId != null) {
            String fields = String.join(",", coercedFields);
            logOnce("coerce|" + configId + "|" + fields, () -> log.warn(
                    "Automation policy for cluster config {} was served with safe values for: {}", configId, fields));
        }
        return b;
    }

    public String toJson(AutomationPolicyBundle bundle) {
        try {
            return mapper.writeValueAsString(bundle);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Automation policy bundle couldn't be serialized", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WRITE PATH
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Validates one section save: readiness refusals first, then locked guardrails, then the §13
     * ranges. Throws {@link K8sAutomationRefusedException}; messages are fixed copy.
     */
    public void validate(Section section, Object body) {
        if (body == null) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.INVALID_POLICY,
                    K8sAutomationReason.INVALID_POLICY.defaultMessage(), section == Section.MODE ? "mode" : null);
        }
        switch (section) {
            case SCALE_OUT -> requireReady(K8sReadinessKey.SCALE_OUT, null);
            case WORKLOAD_RIGHTSIZER -> throw K8sAutomationRefusedException.of(
                    K8sAutomationReason.CONTROLLER_NOT_READY, RETIRED_RIGHTSIZER_MESSAGE);
            case EVICTOR -> validateEvictor(cast(body, EvictorPolicyDto.class));
            case NODE_OPTIMIZER -> validateNodeOptimizer(cast(body, NodeOptimizerPolicyDto.class));
            case SAFETY_GUARDS -> validateSafetyGuards(cast(body, SafetyGuardsDto.class));
            case REBALANCER -> validateRebalancer(cast(body, RebalancerConfigDto.class));
            case MODE -> {
                if (!(body instanceof String mode) || !MODES.contains(mode)) {
                    throw K8sAutomationRefusedException.of(K8sAutomationReason.INVALID_POLICY,
                            "mode must be one of: full, scoped, disabled.", "mode");
                }
            }
        }
    }

    /** {@code POST /rebalance}: refused while {@code rebalancer} isn't ready (D7). */
    public void validateRebalanceTrigger() {
        requireReady(K8sReadinessKey.REBALANCER, null);
    }

    /** Node templates: readiness first (nothing consumes them yet), then the id format. */
    public void validateNodeTemplate(String templateId) {
        requireReady(K8sReadinessKey.NODE_TEMPLATES, null);
        if (templateId == null || !TEMPLATE_ID.matcher(templateId).matches()) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.INVALID_REQUEST,
                    "Template ids are 1-64 letters, digits, hyphens or underscores.", "templateId");
        }
    }

    /**
     * Replaces one section of {@code current} with {@code body} (the UI always sends the complete
     * section, design §6.5). Returns a new bundle; neither argument is modified.
     */
    public AutomationPolicyBundle replaceSection(AutomationPolicyBundle current, Section section, Object body) {
        AutomationPolicyBundle next = copy(current);
        fillMissing(next);
        switch (section) {
            case SCALE_OUT -> next.setScaleOutPolicy(copy(cast(body, ScaleOutPolicyDto.class), ScaleOutPolicyDto.class));
            case EVICTOR -> next.setEvictorPolicy(copy(cast(body, EvictorPolicyDto.class), EvictorPolicyDto.class));
            case WORKLOAD_RIGHTSIZER -> next.setWorkloadRightsizerPolicy(
                    copy(cast(body, WorkloadRightsizerPolicyDto.class), WorkloadRightsizerPolicyDto.class));
            case NODE_OPTIMIZER -> next.setNodeOptimizerPolicy(
                    copy(cast(body, NodeOptimizerPolicyDto.class), NodeOptimizerPolicyDto.class));
            case SAFETY_GUARDS -> {
                SafetyGuardsDto guards = copy(cast(body, SafetyGuardsDto.class), SafetyGuardsDto.class);
                guards.setCriticalNamespaces(guards.getCriticalNamespaces() == null
                        ? next.getSafetyGuards().getCriticalNamespaces()
                        : withRequired(cleanNamespaces(guards.getCriticalNamespaces())));
                next.setSafetyGuards(guards);
            }
            case REBALANCER -> {
                RebalancerConfigDto incoming = copy(cast(body, RebalancerConfigDto.class), RebalancerConfigDto.class);
                RebalancerConfigDto stored = next.getRebalancer();
                incoming.setStatus(stored.getStatus());
                incoming.setLastRebalanceAt(stored.getLastRebalanceAt());
                incoming.setLastRebalanceSavingsPercent(stored.getLastRebalanceSavingsPercent());
                incoming.setLastRebalanceDuration(stored.getLastRebalanceDuration());
                next.setRebalancer(incoming);
            }
            case MODE -> next.setAutoscalerMode(cast(body, String.class));
        }
        return next;
    }

    public AutomationPolicyBundle upsertNodeTemplate(AutomationPolicyBundle current, String templateId,
                                                     NodeTemplateDto template) {
        AutomationPolicyBundle next = copy(current);
        fillMissing(next);
        NodeTemplateDto t = copy(template, NodeTemplateDto.class);
        t.setId(templateId);
        List<NodeTemplateDto> templates = new ArrayList<>(next.getNodeTemplates());
        templates.removeIf(existing -> templateId.equals(existing.getId()));
        templates.add(t);
        next.setNodeTemplates(templates);
        return next;
    }

    public AutomationPolicyBundle deleteNodeTemplate(AutomationPolicyBundle current, String templateId) {
        AutomationPolicyBundle next = copy(current);
        fillMissing(next);
        List<NodeTemplateDto> templates = new ArrayList<>(next.getNodeTemplates());
        templates.removeIf(existing -> templateId.equals(existing.getId()));
        next.setNodeTemplates(templates);
        return next;
    }

    // ── Section validation ────────────────────────────────────────────────

    private void validateEvictor(EvictorPolicyDto p) {
        if (!p.isAnalyseOnly()) {
            requireReady(K8sReadinessKey.EVICTOR_LIVE, "analyseOnly");
        }
        range("targetUtilizationCpu", "Target CPU utilization", p.getTargetUtilizationCpu(), 10, 95);
        range("targetUtilizationMemory", "Target memory utilization", p.getTargetUtilizationMemory(), 10, 95);
        range("nodeGracePeriodMinutes", "Node grace period", p.getNodeGracePeriodMinutes(), 1, 1440);
        range("nodeCycleIntervalSeconds", "Evaluation interval", p.getNodeCycleIntervalSeconds(), 30, 3600);
    }

    private void validateNodeOptimizer(NodeOptimizerPolicyDto p) {
        if (p.isAutoCordonIdleNodes()) {
            requireReady(K8sReadinessKey.NODE_OPTIMIZER_ACTIONS, "autoCordonIdleNodes");
        }
        if (p.isAutoDrainIdleNodes()) {
            requireReady(K8sReadinessKey.NODE_OPTIMIZER_ACTIONS, "autoDrainIdleNodes");
        }
        if (p.getSpotRatioTargetPct() != 0) {
            requireReady(K8sReadinessKey.NODE_OPTIMIZER_ACTIONS, "spotRatioTargetPct");
        }
        if (p.getMaxParallelDrains() != 1) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.GUARDRAIL_LOCKED,
                    "Parallel drains stay at 1 while automation lacks pod-fit simulation.", "maxParallelDrains");
        }
        range("idleCpuThresholdPct", "Idle CPU threshold", p.getIdleCpuThresholdPct(), 1, 50);
        range("idleMemThresholdPct", "Idle memory threshold", p.getIdleMemThresholdPct(), 1, 50);
        range("spotRatioTargetPct", "Spot ratio target", p.getSpotRatioTargetPct(), 0, 100);
    }

    private void validateSafetyGuards(SafetyGuardsDto p) {
        if (!p.isRespectPodDisruptionBudgets()) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.GUARDRAIL_LOCKED,
                    K8sAutomationReason.GUARDRAIL_LOCKED.defaultMessage(), "respectPodDisruptionBudgets");
        }
        if (!p.isProtectCriticalNamespaces()) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.GUARDRAIL_LOCKED,
                    K8sAutomationReason.GUARDRAIL_LOCKED.defaultMessage(), "protectCriticalNamespaces");
        }
        List<String> namespaces = p.getCriticalNamespaces();
        if (namespaces == null) {
            return; // keeps the stored list (design §6.5)
        }
        if (namespaces.size() > MAX_CRITICAL_NAMESPACES) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.INVALID_POLICY,
                    "Protect at most " + MAX_CRITICAL_NAMESPACES + " namespaces.", "criticalNamespaces");
        }
        for (int i = 0; i < namespaces.size(); i++) {
            String ns = namespaces.get(i) == null ? "" : namespaces.get(i).trim().toLowerCase(Locale.ROOT);
            if (!DNS_1123.matcher(ns).matches()) {
                throw K8sAutomationRefusedException.of(K8sAutomationReason.INVALID_POLICY,
                        "Namespace names use lowercase letters, digits and hyphens, up to 63 characters.",
                        "criticalNamespaces[" + i + "]");
            }
        }
    }

    private void validateRebalancer(RebalancerConfigDto p) {
        if (p.isScheduleEnabled()) {
            requireReady(K8sReadinessKey.REBALANCER, "scheduleEnabled");
        }
        if (!p.isGracefulDraining()) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.GUARDRAIL_LOCKED,
                    K8sAutomationReason.GUARDRAIL_LOCKED.defaultMessage(), "gracefulDraining");
        }
        range("drainTimeoutMinutes", "Drain timeout", p.getDrainTimeoutMinutes(), 5, 60);
        if (p.getScheduleCron() != null && p.getScheduleCron().length() > 64) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.INVALID_POLICY,
                    "The schedule is at most 64 characters.", "scheduleCron");
        }
    }

    private void requireReady(K8sReadinessKey key, String field) {
        if (!readiness.isReady(key)) {
            throw K8sAutomationRefusedException.of(key.reason(), key.message(), field);
        }
    }

    private static void range(String field, String label, int value, int min, int max) {
        if (value < min || value > max) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.INVALID_POLICY,
                    label + " must be between " + min + " and " + max + ".", field);
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private record Read(AutomationPolicyBundle bundle, boolean stored) {
    }

    private Read read(String storedJson, Long configId) {
        if (storedJson == null || storedJson.isBlank()) {
            return new Read(defaults(), false);
        }
        AutomationPolicyBundle parsed;
        try {
            parsed = mapper.readValue(storedJson, AutomationPolicyBundle.class);
        } catch (Exception e) {
            parsed = null;
            String error = e.getClass().getSimpleName();
            logOnce("parse|" + configId, () -> log.error(
                    "Stored automation policy for cluster config {} couldn't be parsed ({}); serving the default "
                            + "with autoscalerMode disabled", configId, error));
        }
        if (parsed == null) {
            return new Read(disabledDefault(), false);
        }
        return new Read(normalize(parsed, configId), true);
    }

    private AutomationPolicyBundle disabledDefault() {
        AutomationPolicyBundle b = defaults();
        b.setAutoscalerMode("disabled");
        return b;
    }

    /** Every section non-null; defaults are already safe, so they need no coercion. */
    private static void fillMissing(AutomationPolicyBundle b) {
        if (b.getAutoscalerMode() == null) {
            b.setAutoscalerMode("full");
        }
        if (b.getScaleOutPolicy() == null) {
            b.setScaleOutPolicy(new ScaleOutPolicyDto());
        }
        if (b.getEvictorPolicy() == null) {
            b.setEvictorPolicy(new EvictorPolicyDto());
        }
        if (b.getWorkloadRightsizerPolicy() == null) {
            WorkloadRightsizerPolicyDto retired = new WorkloadRightsizerPolicyDto();
            retired.setEnabled(false);
            retired.setApplyMode("deferred");
            b.setWorkloadRightsizerPolicy(retired);
        }
        if (b.getHpaManagerPolicy() == null) {
            b.setHpaManagerPolicy(new HpaManagerPolicyDto());
        }
        if (b.getVpaManagerPolicy() == null) {
            b.setVpaManagerPolicy(new VpaManagerPolicyDto());
        }
        if (b.getNodeOptimizerPolicy() == null) {
            b.setNodeOptimizerPolicy(new NodeOptimizerPolicyDto());
        }
        if (b.getNodeTemplates() == null) {
            b.setNodeTemplates(new ArrayList<>());
        }
        if (b.getRebalancer() == null) {
            RebalancerConfigDto idle = new RebalancerConfigDto();
            idle.setEnabled(false);
            idle.setScheduleEnabled(false);
            idle.setStatus("idle");
            b.setRebalancer(idle);
        }
        if (b.getSafetyGuards() == null) {
            b.setSafetyGuards(new SafetyGuardsDto());
        }
        if (b.getSafetyGuards().getCriticalNamespaces() == null) {
            b.getSafetyGuards().setCriticalNamespaces(new ArrayList<>(DEFAULT_CRITICAL_NAMESPACES));
        }
    }

    private static List<String> cleanNamespaces(List<String> namespaces) {
        Set<String> cleaned = new LinkedHashSet<>();
        for (String ns : namespaces) {
            if (ns != null && !ns.isBlank()) {
                cleaned.add(ns.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(cleaned);
    }

    private static List<String> withRequired(List<String> namespaces) {
        List<String> out = new ArrayList<>(namespaces == null ? List.of() : namespaces);
        for (String required : REQUIRED_CRITICAL_NAMESPACES) {
            if (!out.contains(required)) {
                out.add(required);
            }
        }
        return out;
    }

    private AutomationPolicyBundle copy(AutomationPolicyBundle bundle) {
        return bundle == null ? new AutomationPolicyBundle() : copy(bundle, AutomationPolicyBundle.class);
    }

    private <T> T copy(T value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    private static <T> T cast(Object body, Class<T> type) {
        if (!type.isInstance(body)) {
            throw new IllegalArgumentException("Expected a " + type.getSimpleName() + " body");
        }
        return type.cast(body);
    }

    private void logOnce(String key, Runnable logLine) {
        if (loggedOnce.size() >= LOG_DEDUP_CAP) {
            loggedOnce.clear();
        }
        if (loggedOnce.add(key)) {
            logLine.run();
        }
    }

    /** For tests: how many distinct log keys are remembered. */
    int loggedKeyCount() {
        return loggedOnce.size();
    }
}
