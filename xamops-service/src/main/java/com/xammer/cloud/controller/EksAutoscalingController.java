package com.xammer.cloud.controller;

import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.*;
import com.xammer.cloud.dto.autoscaling.NodeOptimizerPolicyDto;
import com.xammer.cloud.dto.autoscaling.WorkloadRightsizingDto;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.EksAutoscalingService;
import com.xammer.cloud.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * EksAutoscalingController
 *
 * REST endpoints for the XamOps Autoscaling Agent tab in the EKS Details page.
 *
 * Base path: /api/eks/autoscaling
 * (matches the URL used in the frontend useEksAutoscaling hook)
 *
 * Auth: Same Spring Security session as the rest of the app (no API key needed here —
 * that's only for agent → backend ingest via /api/v1/agent/ingest).
 *
 * Pattern: All write operations are PUT/DELETE, return {success, message}.
 *          GET returns the full AutoscalerDashboardDto.
 *
 * Tenant isolation: Request flows through the existing TenantFilter, so
 * cloudAccountId naturally scopes to the current tenant's data.
 */
@RestController
@RequestMapping("/api/eks/autoscaling")
public class EksAutoscalingController {

    private static final Logger logger = LoggerFactory.getLogger(EksAutoscalingController.class);

    private final EksAutoscalingService autoscalingService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final CloudAccountRepository cloudAccountRepository;

    public EksAutoscalingController(EksAutoscalingService autoscalingService,
                                    KubernetesClusterConfigRepository clusterConfigRepository,
                                    CloudAccountRepository cloudAccountRepository) {
        this.autoscalingService = autoscalingService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void validateAccountOwnership(Long cloudAccountId) {
        if (SecurityUtils.isSuperAdmin()) {
            return;
        }
        String awsAccountIdPadded = String.format("%012d", cloudAccountId);
        if (cloudAccountRepository.findByAwsAccountIdAndClientId(
                awsAccountIdPadded, SecurityUtils.getPrincipal().getClientId()).isEmpty()) {
            throw new AccessDeniedException("You do not have permission to access this cloud account.");
        }
    }

    private static Map<String, Object> ok(String message) {
        return Map.of("success", true, "message", message);
    }

    private static Map<String, Object> err(String message) {
        return Map.of("success", false, "message", message);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET DASHBOARD
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * GET /api/eks/autoscaling/{clusterName}?accountId=xxx&region=xxx
     *
     * Returns the full AutoscalerDashboardDto.
     * If autoscaling is not enabled → returns a lightweight disabled-state object.
     * If cluster not found → 404.
     */
    @GetMapping("/{clusterName}")
    public ResponseEntity<?> getDashboard(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam(required = false, defaultValue = "us-east-1") String region) {

        logger.debug("GET autoscaling dashboard: cluster={} account={} region={}",
                clusterName, accountId, region);

        try {
            Long cloudAccountId = Long.parseLong(accountId);
            validateAccountOwnership(cloudAccountId);
            AutoscalerDashboardDto dto =
                    autoscalingService.getDashboard(clusterName, cloudAccountId, region);

            if (dto == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(dto);

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(err("accountId must be a number"));
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting autoscaling dashboard for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POLICY SAVES
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * PUT /api/eks/autoscaling/{clusterName}/scale-out-policy?accountId=xxx
     */
    @PutMapping("/{clusterName}/scale-out-policy")
    public ResponseEntity<Map<String, Object>> saveScaleOutPolicy(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestBody ScaleOutPolicyDto policy) {

        logger.info("PUT scale-out-policy: cluster={} account={}", clusterName, accountId);
        try {
            autoscalingService.saveScaleOutPolicy(clusterName, Long.parseLong(accountId), policy);
            return ResponseEntity.ok(ok("Scale-Out policy saved"));
        } catch (Exception e) {
            logger.error("Error saving scale-out-policy for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /**
     * PUT /api/eks/autoscaling/{clusterName}/evictor-policy?accountId=xxx
     */
    @PutMapping("/{clusterName}/evictor-policy")
    public ResponseEntity<Map<String, Object>> saveEvictorPolicy(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestBody EvictorPolicyDto policy) {

        logger.info("PUT evictor-policy: cluster={} account={}", clusterName, accountId);
        try {
            autoscalingService.saveEvictorPolicy(clusterName, Long.parseLong(accountId), policy);
            return ResponseEntity.ok(ok("Evictor policy saved"));
        } catch (Exception e) {
            logger.error("Error saving evictor-policy for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /**
     * PUT /api/eks/autoscaling/{clusterName}/workload-rightsizer?accountId=xxx
     */
    @PutMapping("/{clusterName}/workload-rightsizer")
    public ResponseEntity<Map<String, Object>> saveWorkloadRightsizer(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestBody WorkloadRightsizerPolicyDto policy) {

        logger.info("PUT workload-rightsizer: cluster={} account={}", clusterName, accountId);
        try {
            autoscalingService.saveWorkloadRightsizerPolicy(clusterName, Long.parseLong(accountId), policy);
            return ResponseEntity.ok(ok("Workload Rightsizer policy saved"));
        } catch (Exception e) {
            logger.error("Error saving workload-rightsizer for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /**
     * PUT /api/eks/autoscaling/{clusterName}/safety-guards?accountId=xxx
     */
    @PutMapping("/{clusterName}/safety-guards")
    public ResponseEntity<Map<String, Object>> saveSafetyGuards(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestBody SafetyGuardsDto guards) {

        logger.info("PUT safety-guards: cluster={} account={}", clusterName, accountId);
        try {
            autoscalingService.saveSafetyGuards(clusterName, Long.parseLong(accountId), guards);
            return ResponseEntity.ok(ok("Safety Guards saved"));
        } catch (Exception e) {
            logger.error("Error saving safety-guards for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /**
     * PUT /api/eks/autoscaling/{clusterName}/node-templates/{templateId}?accountId=xxx
     */
    @PutMapping("/{clusterName}/node-templates/{templateId}")
    public ResponseEntity<Map<String, Object>> saveNodeTemplate(
            @PathVariable String clusterName,
            @PathVariable String templateId,
            @RequestParam String accountId,
            @RequestBody NodeTemplateDto template) {

        logger.info("PUT node-template {}: cluster={} account={}", templateId, clusterName, accountId);
        try {
            autoscalingService.saveNodeTemplate(clusterName, Long.parseLong(accountId), templateId, template);
            return ResponseEntity.ok(ok("Node template saved"));
        } catch (Exception e) {
            logger.error("Error saving node-template {} for cluster {}: {}", templateId, clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /**
     * DELETE /api/eks/autoscaling/{clusterName}/node-templates/{templateId}?accountId=xxx
     */
    @DeleteMapping("/{clusterName}/node-templates/{templateId}")
    public ResponseEntity<Map<String, Object>> deleteNodeTemplate(
            @PathVariable String clusterName,
            @PathVariable String templateId,
            @RequestParam String accountId) {

        logger.info("DELETE node-template {}: cluster={} account={}", templateId, clusterName, accountId);
        try {
            autoscalingService.deleteNodeTemplate(clusterName, Long.parseLong(accountId), templateId);
            return ResponseEntity.ok(ok("Node template deleted"));
        } catch (Exception e) {
            logger.error("Error deleting node-template {} for cluster {}: {}", templateId, clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /**
     * PUT /api/eks/autoscaling/{clusterName}/node-optimizer?accountId=xxx
     */
    @PutMapping("/{clusterName}/node-optimizer")
    public ResponseEntity<Map<String, Object>> saveNodeOptimizerPolicy(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestBody NodeOptimizerPolicyDto policy) {

        logger.info("PUT node-optimizer: cluster={} account={}", clusterName, accountId);
        try {
            autoscalingService.saveNodeOptimizerPolicy(clusterName, Long.parseLong(accountId), policy);
            return ResponseEntity.ok(ok("Node Optimizer policy saved"));
        } catch (Exception e) {
            logger.error("Error saving node-optimizer for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /**
     * PUT /api/eks/autoscaling/{clusterName}/rebalancer?accountId=xxx
     */
    @PutMapping("/{clusterName}/rebalancer")
    public ResponseEntity<Map<String, Object>> saveRebalancer(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestBody RebalancerConfigDto config) {

        logger.info("PUT rebalancer: cluster={} account={}", clusterName, accountId);
        try {
            autoscalingService.saveRebalancerConfig(clusterName, Long.parseLong(accountId), config);
            return ResponseEntity.ok(ok("Rebalancer config saved"));
        } catch (Exception e) {
            logger.error("Error saving rebalancer for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /**
     * PUT /api/eks/autoscaling/{clusterName}/mode?accountId=xxx
     * Body: { "mode": "full" | "scoped" | "disabled" }
     */
    @PutMapping("/{clusterName}/mode")
    public ResponseEntity<Map<String, Object>> setMode(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestBody Map<String, String> body) {

        String mode = body.get("mode");
        logger.info("PUT mode={}: cluster={} account={}", mode, clusterName, accountId);

        if (mode == null || (!mode.equals("full") && !mode.equals("scoped") && !mode.equals("disabled"))) {
            return ResponseEntity.badRequest().body(err("mode must be one of: full, scoped, disabled"));
        }
        try {
            autoscalingService.setMode(clusterName, Long.parseLong(accountId), mode);
            return ResponseEntity.ok(ok("Autoscaler mode set to: " + mode));
        } catch (Exception e) {
            logger.error("Error setting mode for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REBALANCE TRIGGER
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * POST /api/eks/autoscaling/{clusterName}/rebalance?accountId=xxx
     *
     * Triggers a manual rebalance. Stores a "running" status in agent_data_cache.
     * The XamOps agent picks this up on its next heartbeat and executes the rebalance.
     */
    @PostMapping("/{clusterName}/rebalance")
    public ResponseEntity<Map<String, Object>> triggerRebalance(
            @PathVariable String clusterName,
            @RequestParam String accountId) {

        logger.info("POST rebalance: cluster={} account={}", clusterName, accountId);
        try {
            autoscalingService.triggerRebalance(clusterName, Long.parseLong(accountId));
            return ResponseEntity.ok(ok("Rebalance triggered — agent will execute on next sync"));
        } catch (Exception e) {
            logger.error("Error triggering rebalance for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ENABLE / DISABLE (used by cluster setup / integration guide)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * PUT /api/eks/autoscaling/{clusterName}/enabled?accountId=xxx
     * Body: { "enabled": true | false }
     *
     * Enables or disables the autoscaling agent flag on the cluster config.
     * Once enabled, the cluster's autoscaling data flows through agent_data_cache.
     */
    @PutMapping("/{clusterName}/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestBody Map<String, Boolean> body) {

        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        logger.info("PUT autoscaling enabled={}: cluster={} account={}", enabled, clusterName, accountId);

        try {
            Long cloudAccountId = Long.parseLong(accountId);
            var configOpt = clusterConfigRepository.findByCloudAccountIdAndClusterName(cloudAccountId, clusterName);
            if (configOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            autoscalingService.setAutoscalingEnabled(configOpt.get().getId(), enabled);
            return ResponseEntity.ok(ok("Autoscaling " + (enabled ? "enabled" : "disabled")));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(err("accountId must be a number"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.error("Error setting autoscaling enabled for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        } catch (RuntimeException e) {
            logger.error("Error setting autoscaling enabled for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // XAMOPS-AUTOSCALER CONTROLLER DATA (read by UI, pushed by autoscaler)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * GET /api/eks/autoscaling/{clusterName}/rightsizing?accountId=xxx
     *
     * Returns workload rightsizing recommendations pushed by xamops-automation.
     * Empty list if autoscaler has not run yet.
     */
    @GetMapping("/{clusterName}/rightsizing")
    public ResponseEntity<WorkloadRightsizingDto> getRightsizing(
            @PathVariable String clusterName,
            @RequestParam String accountId) {

        logger.debug("GET rightsizing: cluster={} account={}", clusterName, accountId);
        try {
            WorkloadRightsizingDto dto = autoscalingService.getRightsizingData(
                    clusterName, Long.parseLong(accountId));
            return ResponseEntity.ok(dto);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error getting rightsizing for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/eks/autoscaling/{clusterName}/rebalance-status?accountId=xxx
     *
     * Returns the current rebalance status pushed by xamops-automation.
     */
    @GetMapping("/{clusterName}/rebalance-status")
    public ResponseEntity<Map<String, Object>> getRebalanceStatus(
            @PathVariable String clusterName,
            @RequestParam String accountId) {

        logger.debug("GET rebalance-status: cluster={} account={}", clusterName, accountId);
        try {
            Map<String, Object> status = autoscalingService.getRebalanceStatus(
                    clusterName, Long.parseLong(accountId));
            return ResponseEntity.ok(status);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error getting rebalance status for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/eks/autoscaling/{clusterName}/node-fleet?accountId=xxx
     *
     * Returns a NodeFleetReport — on-demand/spot split, idle nodes, and spot candidates.
     * Synthesized from k8s-resources agent cache if xamops-automation has not pushed one yet.
     */
    @GetMapping("/{clusterName}/node-fleet")
    public ResponseEntity<Map<String, Object>> getNodeFleet(
            @PathVariable String clusterName,
            @RequestParam String accountId) {

        logger.debug("GET node-fleet: cluster={} account={}", clusterName, accountId);
        try {
            Map<String, Object> report = autoscalingService.getNodeFleetReport(
                    clusterName, Long.parseLong(accountId));
            return ResponseEntity.ok(report);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error getting node-fleet for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/eks/autoscaling/{clusterName}/evictor-candidates?accountId=xxx
     *
     * Returns nodes the evictor identified as consolidation candidates.
     */
    @GetMapping("/{clusterName}/evictor-candidates")
    public ResponseEntity<List<Map<String, Object>>> getEvictorCandidates(
            @PathVariable String clusterName,
            @RequestParam String accountId) {

        logger.debug("GET evictor-candidates: cluster={} account={}", clusterName, accountId);
        try {
            List<Map<String, Object>> candidates = autoscalingService.getEvictorCandidates(
                    clusterName, Long.parseLong(accountId));
            return ResponseEntity.ok(candidates);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error getting evictor candidates for cluster {}: {}", clusterName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
