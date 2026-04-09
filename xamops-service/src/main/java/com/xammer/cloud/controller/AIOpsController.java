package com.xammer.cloud.controller;

import com.xammer.cloud.dto.AIOPsDashboardDto;
import com.xammer.cloud.dto.AIOPsDashboardDto.*;
import com.xammer.cloud.dto.LlmProviderConfigDto;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AIOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.xammer.cloud.dto.AIOPsDashboardDto.AgentUsageStat;
import com.xammer.cloud.dto.AIOPsDashboardDto.ModelComparisonStat;
import com.xammer.cloud.dto.AIOPsDashboardDto.HeatmapCell;
import com.xammer.cloud.dto.AIOPsDashboardDto.FilterOptions;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/xamops/aiops")
public class AIOpsController {

    private static final Logger logger = LoggerFactory.getLogger(AIOpsController.class);

    private final AIOpsService aiOpsService;

    public AIOpsController(AIOpsService aiOpsService) {
        this.aiOpsService = aiOpsService;
    }

    // ─── Dashboard Endpoints ─────────────────────────────────────

    @GetMapping("/dashboard")
    public ResponseEntity<AIOPsDashboardDto> getFullDashboard(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(defaultValue = "30d") String period) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getFullDashboard(clientId, period));
        } catch (Exception e) {
            logger.error("Error fetching AIOps dashboard: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/kpis")
    public ResponseEntity<LlmKpis> getKpis(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(defaultValue = "30d") String period) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getKpis(clientId, period));
        } catch (Exception e) {
            logger.error("Error fetching AIOps KPIs: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/cost-trend")
    public ResponseEntity<CostTrend> getCostTrend(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(defaultValue = "30d") String period) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getCostTrend(clientId, period));
        } catch (Exception e) {
            logger.error("Error fetching cost trend: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/provider-breakdown")
    public ResponseEntity<List<ProviderCostBreakdown>> getProviderBreakdown(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(defaultValue = "30d") String period) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getProviderBreakdown(clientId, period));
        } catch (Exception e) {
            logger.error("Error fetching provider breakdown: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/model-usage")
    public ResponseEntity<List<ModelUsageStat>> getModelUsage(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(defaultValue = "30d") String period) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getModelUsage(clientId, period));
        } catch (Exception e) {
            logger.error("Error fetching model usage: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/anomalies")
    public ResponseEntity<List<CostAnomaly>> getAnomalies(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(defaultValue = "30d") String period) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getAnomalies(clientId, period));
        } catch (Exception e) {
            logger.error("Error fetching anomalies: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ─── Usage Analytics Endpoints ──────────────────────────────

    @GetMapping("/request-logs")
    public ResponseEntity<Map<String, Object>> getRequestLogs(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(defaultValue = "30d") String period,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String agent,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getRequestLogs(clientId, period, provider, model, agent, page, size));
        } catch (Exception e) {
            logger.error("Error fetching request logs: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/agent-breakdown")
    public ResponseEntity<List<AgentUsageStat>> getAgentBreakdown(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(defaultValue = "30d") String period,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getAgentBreakdown(clientId, period, provider, model));
        } catch (Exception e) {
            logger.error("Error fetching agent breakdown: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/model-comparison")
    public ResponseEntity<List<ModelComparisonStat>> getModelComparison(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(defaultValue = "30d") String period,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String agent) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getModelComparison(clientId, period, provider, agent));
        } catch (Exception e) {
            logger.error("Error fetching model comparison: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/token-heatmap")
    public ResponseEntity<List<HeatmapCell>> getTokenHeatmap(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(defaultValue = "30d") String period,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String agent) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getTokenHeatmap(clientId, period, provider, model, agent));
        } catch (Exception e) {
            logger.error("Error fetching token heatmap: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/filter-options")
    public ResponseEntity<FilterOptions> getFilterOptions(
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getFilterOptions(clientId));
        } catch (Exception e) {
            logger.error("Error fetching filter options: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/all-time-metrics")
    public ResponseEntity<AllTimeMetrics> getAllTimeMetrics(
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getAllTimeMetrics(clientId));
        } catch (Exception e) {
            logger.error("Error fetching all-time metrics: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ─── Budget & Controls Endpoints ────────────────────────────

    @GetMapping("/budgets/summary")
    public ResponseEntity<BudgetSummaryDto> getBudgetSummary(
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getBudgetSummary(clientId));
        } catch (Exception e) {
            logger.error("Error fetching budget summary: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/budgets")
    public ResponseEntity<List<BudgetConfigDto>> getBudgets(
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getBudgets(clientId));
        } catch (Exception e) {
            logger.error("Error fetching budgets: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/budgets")
    public ResponseEntity<BudgetConfigDto> createBudget(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestBody BudgetCreateRequest request) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.createBudget(clientId, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error creating budget: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/budgets/{id}")
    public ResponseEntity<BudgetConfigDto> updateBudget(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @PathVariable Long id,
            @RequestBody BudgetUpdateRequest request) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.updateBudget(clientId, id, request));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        } catch (Exception e) {
            logger.error("Error updating budget: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/budgets/{id}")
    public ResponseEntity<Void> deleteBudget(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @PathVariable Long id) {
        try {
            Long clientId = userDetails.getClientId();
            aiOpsService.deleteBudget(clientId, id);
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        } catch (Exception e) {
            logger.error("Error deleting budget: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/budgets/alerts")
    public ResponseEntity<List<BudgetAlertDto>> getBudgetAlerts(
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getBudgetAlerts(clientId));
        } catch (Exception e) {
            logger.error("Error fetching budget alerts: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/budgets/alerts/{id}/dismiss")
    public ResponseEntity<Void> dismissAlert(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @PathVariable Long id) {
        try {
            Long clientId = userDetails.getClientId();
            aiOpsService.dismissAlert(clientId, id);
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        } catch (Exception e) {
            logger.error("Error dismissing alert: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/budgets/{id}/daily-spend")
    public ResponseEntity<List<DailySpendEntry>> getBudgetDailySpend(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @PathVariable Long id) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getBudgetDailySpend(clientId, id));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        } catch (Exception e) {
            logger.error("Error fetching budget daily spend: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ─── Provider Config Endpoints ───────────────────────────────

    @GetMapping("/providers")
    public ResponseEntity<List<LlmProviderConfigDto>> getProviders(
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.getProviders(clientId));
        } catch (Exception e) {
            logger.error("Error fetching providers: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/providers")
    public ResponseEntity<LlmProviderConfigDto> addProvider(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestBody LlmProviderConfigDto dto) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.addProvider(clientId, dto));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error adding provider: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/providers/{id}")
    public ResponseEntity<LlmProviderConfigDto> updateProvider(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @PathVariable Long id,
            @RequestBody LlmProviderConfigDto dto) {
        try {
            Long clientId = userDetails.getClientId();
            return ResponseEntity.ok(aiOpsService.updateProvider(clientId, id, dto));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        } catch (Exception e) {
            logger.error("Error updating provider: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/providers/{id}")
    public ResponseEntity<Void> deleteProvider(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @PathVariable Long id) {
        try {
            Long clientId = userDetails.getClientId();
            aiOpsService.deleteProvider(clientId, id);
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        } catch (Exception e) {
            logger.error("Error deleting provider: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/providers/test")
    public ResponseEntity<Map<String, Object>> testConnection(
            @RequestBody LlmProviderConfigDto dto) {
        try {
            return ResponseEntity.ok(aiOpsService.testConnection(dto));
        } catch (Exception e) {
            logger.error("Error testing connection: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
