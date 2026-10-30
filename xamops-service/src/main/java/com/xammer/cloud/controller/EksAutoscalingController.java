package com.xammer.cloud.controller;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.*;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanResponse;
import com.xammer.cloud.dto.autoscaling.ConsolidationPolicyDto;
import com.xammer.cloud.dto.autoscaling.ConsolidationRunResponse;
import com.xammer.cloud.dto.autoscaling.NodeOptimizerPolicyDto;
import com.xammer.cloud.dto.autoscaling.VpaManagerPolicyDto;
import com.xammer.cloud.dto.autoscaling.VpaRunResponse;
import com.xammer.cloud.dto.autoscaling.WorkloadRightsizingDto;
import com.xammer.cloud.dto.k8s.K8sRefusalBody;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.security.K8sAutomationRequestContext;
import com.xammer.cloud.service.ConsolidationPlanService;
import com.xammer.cloud.service.ConsolidationRunService;
import com.xammer.cloud.service.EksAutoscalingService;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer.Section;
import com.xammer.cloud.service.spot.SpotInterruptionReadService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

/**
 * EksAutoscalingController
 *
 * REST endpoints for node automation on the EKS cluster page. Base path: /api/eks/autoscaling.
 *
 * <p>
 * Every request passes {@code K8sAutomationGuardInterceptor} first (design §6.6): it validates the
 * cluster name and {@code accountId}, resolves the caller's entitled account and the cluster
 * config, refuses unregistered clusters, and runs the write gates on every mutation. Handlers read
 * the resolved {@link K8sAutomationRequestContext} and never parse {@code accountId} themselves.
 *
 * <p>
 * Readiness refusals (design §6.3, §6.7) happen here, before the service is called. Section saves
 * replace the whole section: send the complete section, the current value with your changes
 * applied (design §6.5); an omitted or null {@code safetyGuards.criticalNamespaces} keeps the
 * stored list. Successes return {@code {success: true, message}}; refusals return
 * {@code {success: false, code, message, field, detail}} with fixed messages.
 */
@RestController
@RequestMapping("/api/eks/autoscaling")
public class EksAutoscalingController {

    private static final Logger logger = LoggerFactory.getLogger(EksAutoscalingController.class);

    private final EksAutoscalingService autoscalingService;
    private final K8sAutomationPolicyNormalizer normalizer;
    private final ConsolidationPlanService consolidationPlanService;
    private final ConsolidationRunService consolidationRunService;
    private final SpotInterruptionReadService spotInterruptionReadService;

    public EksAutoscalingController(EksAutoscalingService autoscalingService,
                                    K8sAutomationPolicyNormalizer normalizer,
                                    ConsolidationPlanService consolidationPlanService,
                                    ConsolidationRunService consolidationRunService,
                                    SpotInterruptionReadService spotInterruptionReadService) {
        this.autoscalingService = autoscalingService;
        this.normalizer = normalizer;
        this.consolidationPlanService = consolidationPlanService;
        this.consolidationRunService = consolidationRunService;
        this.spotInterruptionReadService = spotInterruptionReadService;
    }

    private static Map<String, Object> ok(String message) {
        return Map.of("success", true, "message", message);
    }

    private static KubernetesClusterConfig config(K8sAutomationRequestContext ctx) {
        return ctx.config();
    }

    private static String actor(K8sAutomationRequestContext ctx) {
        return ctx.user().getUsername();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // READS
    // ═══════════════════════════════════════════════════════════════════════

    /** GET /api/eks/autoscaling/{clusterName}?accountId=xxx: every section, enabled or not. */
    @GetMapping("/{clusterName}")
    public ResponseEntity<AutoscalerDashboardDto> getDashboard(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx) {
        return ResponseEntity.ok(autoscalingService.getDashboard(config(ctx)));
    }

    /** GET /api/eks/autoscaling/{clusterName}/rightsizing?accountId=xxx (no longer used by the UI). */
    @GetMapping("/{clusterName}/rightsizing")
    public ResponseEntity<WorkloadRightsizingDto> getRightsizing(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx) {
        return ResponseEntity.ok(autoscalingService.getRightsizingData(config(ctx)));
    }

    /** GET /api/eks/autoscaling/{clusterName}/rebalance-status?accountId=xxx */
    @GetMapping("/{clusterName}/rebalance-status")
    public ResponseEntity<Map<String, Object>> getRebalanceStatus(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx) {
        return ResponseEntity.ok(autoscalingService.getRebalanceStatus(config(ctx)));
    }

    /** GET /api/eks/autoscaling/{clusterName}/node-fleet?accountId=xxx */
    @GetMapping("/{clusterName}/node-fleet")
    public ResponseEntity<Map<String, Object>> getNodeFleet(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx) {
        return ResponseEntity.ok(autoscalingService.getNodeFleetReport(config(ctx)));
    }

    /** GET /api/eks/autoscaling/{clusterName}/evictor-candidates?accountId=xxx */
    @GetMapping("/{clusterName}/evictor-candidates")
    public ResponseEntity<List<Map<String, Object>>> getEvictorCandidates(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx) {
        return ResponseEntity.ok(autoscalingService.getEvictorCandidates(config(ctx)));
    }

    /** GET /api/eks/autoscaling/{clusterName}/consolidation-plan?accountId=xxx — look-only, never acts */
    @GetMapping("/{clusterName}/consolidation-plan")
    public ResponseEntity<ConsolidationPlanResponse> getConsolidationPlan(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx) {
        return ResponseEntity.ok(consolidationPlanService.getPlan(config(ctx), ctx.primaryAccount()));
    }

    /** GET /api/eks/autoscaling/{clusterName}/consolidation-runs?accountId=xxx — run history, never acts */
    @GetMapping("/{clusterName}/consolidation-runs")
    public ResponseEntity<ConsolidationRunResponse> getConsolidationRuns(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx) {
        return ResponseEntity.ok(consolidationRunService.getRuns(config(ctx), ctx.primaryAccount()));
    }

    /** GET /api/eks/autoscaling/{clusterName}/vpa-runs?accountId=xxx — VPA resize run history, never acts */
    @GetMapping("/{clusterName}/vpa-runs")
    public ResponseEntity<VpaRunResponse> vpaRuns(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx) {
        return ResponseEntity.ok(autoscalingService.readVpaRuns(config(ctx)));
    }

    /**
     * GET /api/eks/autoscaling/{clusterName}/spot-interruptions?accountId=xxx&days=30&limit=200:
     * recorded Spot interruptions, Spot share and producer coverage (design §B5). Read-only.
     * {@code days} (1..365, default 30) and {@code limit} (1..500, default 200) are bound as
     * strings so a non-integer is refused with {@code INVALID_REQUEST} and its field.
     */
    @GetMapping("/{clusterName}/spot-interruptions")
    public ResponseEntity<SpotInterruptionsDto> getSpotInterruptions(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestParam(name = "days", required = false) String days,
            @RequestParam(name = "limit", required = false) String limit) {
        int d = wholeNumber(days, 30, 1, 365, "days", "days must be a whole number from 1 to 365.");
        int l = wholeNumber(limit, 200, 1, 500, "limit", "limit must be a whole number from 1 to 500.");
        return ResponseEntity.ok(spotInterruptionReadService.read(config(ctx), d, l));
    }

    private static int wholeNumber(String raw, int defaultValue, int min, int max, String field, String message) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // refused below with the same fixed message
        }
        throw K8sAutomationRefusedException.of(K8sAutomationReason.INVALID_REQUEST, message, field);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NODE AUTOMATION ON/OFF AND SCOPE
    // ═══════════════════════════════════════════════════════════════════════

    /** PUT /api/eks/autoscaling/{clusterName}/enabled?accountId=xxx, body {"enabled": true|false} (required). */
    @PutMapping("/{clusterName}/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestBody Map<String, Object> body) {
        Object enabled = body.get("enabled");
        if (!(enabled instanceof Boolean on)) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.INVALID_POLICY,
                    "enabled must be true or false.", "enabled");
        }
        autoscalingService.setAutoscalingEnabled(config(ctx), on, actor(ctx));
        return ResponseEntity.ok(ok("Node automation turned " + (on ? "on" : "off")));
    }

    /** PUT /api/eks/autoscaling/{clusterName}/mode?accountId=xxx, body {"mode": "full" | "scoped" | "disabled"}. */
    @PutMapping("/{clusterName}/mode")
    public ResponseEntity<Map<String, Object>> setMode(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestBody Map<String, Object> body) {
        Object mode = body.get("mode");
        normalizer.validate(Section.MODE, mode);
        autoscalingService.setMode(config(ctx), (String) mode, actor(ctx));
        return ResponseEntity.ok(ok("Node automation scope set to " + mode));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POLICY SAVES (each replaces its whole section)
    // ═══════════════════════════════════════════════════════════════════════

    /** PUT …/scale-out-policy: refused while {@code scale-out} isn't ready (the agent doesn't provision nodes). */
    @PutMapping("/{clusterName}/scale-out-policy")
    public ResponseEntity<Map<String, Object>> saveScaleOutPolicy(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestBody ScaleOutPolicyDto policy) {
        normalizer.validate(Section.SCALE_OUT, policy);
        autoscalingService.saveScaleOutPolicy(config(ctx), policy, actor(ctx));
        return ResponseEntity.ok(ok("Scale-out policy saved"));
    }

    /** PUT …/evictor-policy: {@code analyseOnly: false} is refused while {@code evictor-live} isn't ready. */
    @PutMapping("/{clusterName}/evictor-policy")
    public ResponseEntity<Map<String, Object>> saveEvictorPolicy(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestBody EvictorPolicyDto policy) {
        normalizer.validate(Section.EVICTOR, policy);
        autoscalingService.saveEvictorPolicy(config(ctx), policy, actor(ctx));
        return ResponseEntity.ok(ok("Evictor policy saved"));
    }

    /** PUT …/workload-rightsizer: always refused; the Go rightsizer is retired. */
    @PutMapping("/{clusterName}/workload-rightsizer")
    public ResponseEntity<Map<String, Object>> saveWorkloadRightsizer(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestBody WorkloadRightsizerPolicyDto policy) {
        normalizer.validate(Section.WORKLOAD_RIGHTSIZER, policy);
        autoscalingService.saveWorkloadRightsizerPolicy(config(ctx), policy, actor(ctx));
        return ResponseEntity.ok(ok("Workload rightsizer policy saved"));
    }

    /** PUT …/safety-guards: locked guardrails can't be loosened; a null criticalNamespaces keeps the stored list. */
    @PutMapping("/{clusterName}/safety-guards")
    public ResponseEntity<Map<String, Object>> saveSafetyGuards(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestBody SafetyGuardsDto guards) {
        normalizer.validate(Section.SAFETY_GUARDS, guards);
        autoscalingService.saveSafetyGuards(config(ctx), guards, actor(ctx));
        return ResponseEntity.ok(ok("Safety guards saved"));
    }

    /** PUT …/node-optimizer: cordon, drain and spot-ratio fields are refused while not ready. */
    @PutMapping("/{clusterName}/node-optimizer")
    public ResponseEntity<Map<String, Object>> saveNodeOptimizerPolicy(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestBody NodeOptimizerPolicyDto policy) {
        normalizer.validate(Section.NODE_OPTIMIZER, policy);
        autoscalingService.saveNodeOptimizerPolicy(config(ctx), policy, actor(ctx));
        return ResponseEntity.ok(ok("Node optimizer policy saved"));
    }

    /** PUT …/rebalancer: {@code scheduleEnabled: true} is refused while not ready; status fields are server-owned. */
    @PutMapping("/{clusterName}/rebalancer")
    public ResponseEntity<Map<String, Object>> saveRebalancer(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestBody RebalancerConfigDto rebalancer) {
        normalizer.validate(Section.REBALANCER, rebalancer);
        autoscalingService.saveRebalancerConfig(config(ctx), rebalancer, actor(ctx));
        return ResponseEntity.ok(ok("Rebalancer config saved"));
    }

    /** PUT …/consolidation: {@code enabled: true} is refused while {@code consolidation-actions} isn't ready. */
    @PutMapping("/{clusterName}/consolidation")
    public ResponseEntity<Map<String, Object>> saveConsolidationPolicy(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestBody ConsolidationPolicyDto policy) {
        normalizer.validate(Section.CONSOLIDATION, policy);
        autoscalingService.saveConsolidationPolicy(config(ctx), policy, actor(ctx));
        return ResponseEntity.ok(ok("Consolidation policy saved"));
    }

    /**
     * PUT …/vpa-manager: an acting policy (enabled && !analyseOnly && an acting updateMode) is
     * refused while {@code vpa-apply} isn't ready; a stored acting policy is still served inert by
     * the coercion until the key flips. Mirrors {@link #saveEvictorPolicy}.
     */
    @PutMapping("/{clusterName}/vpa-manager")
    public ResponseEntity<Map<String, Object>> saveVpaManagerPolicy(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestBody VpaManagerPolicyDto policy) {
        normalizer.validate(Section.VPA_MANAGER, policy);
        autoscalingService.saveVpaManagerPolicy(config(ctx), policy, actor(ctx));
        return ResponseEntity.ok(ok("VPA manager policy saved"));
    }

    /** PUT …/node-templates/{templateId}: refused while {@code node-templates} isn't ready. */
    @PutMapping("/{clusterName}/node-templates/{templateId}")
    public ResponseEntity<Map<String, Object>> saveNodeTemplate(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @PathVariable String templateId,
            @RequestBody NodeTemplateDto template) {
        normalizer.validateNodeTemplate(templateId);
        autoscalingService.saveNodeTemplate(config(ctx), templateId, template, actor(ctx));
        return ResponseEntity.ok(ok("Node template saved"));
    }

    /** DELETE …/node-templates/{templateId}: refused while {@code node-templates} isn't ready. */
    @DeleteMapping("/{clusterName}/node-templates/{templateId}")
    public ResponseEntity<Map<String, Object>> deleteNodeTemplate(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @PathVariable String templateId) {
        normalizer.validateNodeTemplate(templateId);
        autoscalingService.deleteNodeTemplate(config(ctx), templateId, actor(ctx));
        return ResponseEntity.ok(ok("Node template deleted"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REBALANCE TRIGGER
    // ═══════════════════════════════════════════════════════════════════════

    /** POST …/rebalance: refused while {@code rebalancer} isn't ready (design D7). */
    @PostMapping("/{clusterName}/rebalance")
    public ResponseEntity<Map<String, Object>> triggerRebalance(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx) {
        normalizer.validateRebalanceTrigger();
        autoscalingService.triggerRebalance(config(ctx), actor(ctx));
        return ResponseEntity.ok(ok("Rebalance requested"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ERRORS: fixed messages, never e.getMessage() (design §6.6)
    // ═══════════════════════════════════════════════════════════════════════

    @ExceptionHandler(K8sAutomationRefusedException.class)
    public ResponseEntity<K8sRefusalBody> handleRefused(K8sAutomationRefusedException e, HttpServletRequest request) {
        K8sAutomationResponses.logRefusal(logger, request, e);
        return K8sAutomationResponses.refusal(e);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<K8sRefusalBody> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        String field = K8sAutomationResponses.jsonField(e);
        logger.warn("K8s automation body rejected: method={} path={} field={}", request.getMethod(),
                request.getRequestURI(), field);
        return K8sAutomationResponses.refusal(K8sAutomationReason.INVALID_POLICY,
                K8sAutomationReason.INVALID_POLICY.defaultMessage(), field);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<K8sRefusalBody> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                             HttpServletRequest request) {
        logger.warn("K8s automation parameter rejected: method={} path={} field={}", request.getMethod(),
                request.getRequestURI(), e.getName());
        return K8sAutomationResponses.refusal(K8sAutomationReason.INVALID_POLICY,
                K8sAutomationReason.INVALID_POLICY.defaultMessage(), e.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<K8sRefusalBody> handleMissingParameter(MissingServletRequestParameterException e,
                                                                 HttpServletRequest request) {
        logger.warn("K8s automation parameter missing: method={} path={} field={}", request.getMethod(),
                request.getRequestURI(), e.getParameterName());
        return K8sAutomationResponses.refusal(K8sAutomationReason.INVALID_REQUEST,
                K8sAutomationReason.INVALID_REQUEST.defaultMessage(), e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<K8sRefusalBody> handleUnexpected(Exception e, HttpServletRequest request) {
        logger.error("K8s automation request failed: method={} path={}", request.getMethod(), request.getRequestURI(), e);
        return K8sAutomationResponses.internal(request);
    }
}
