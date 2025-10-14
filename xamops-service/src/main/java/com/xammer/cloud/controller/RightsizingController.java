package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import com.xammer.cloud.service.OptimizationService;
import com.xammer.cloud.service.XamOpsRightsizingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops")
public class RightsizingController {

    private static final Logger logger = LoggerFactory.getLogger(RightsizingController.class);

    private final OptimizationService optimizationService;
    private final XamOpsRightsizingService xamOpsRightsizingService;

    public RightsizingController(OptimizationService optimizationService,
                                 XamOpsRightsizingService xamOpsRightsizingService) {
        this.optimizationService = optimizationService;
        this.xamOpsRightsizingService = xamOpsRightsizingService;
    }

    /**
     * Get AWS native rightsizing recommendations
     */
    @GetMapping("/rightsizing/recommendations")
    public CompletableFuture<ResponseEntity<List<DashboardData.OptimizationRecommendation>>> getRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        logger.info("📊 Fetching AWS rightsizing recommendations for account: {}", accountId);
        return optimizationService.getAllOptimizationRecommendations(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching optimization recommendations for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    /**
     * Get XamOps custom rightsizing recommendations (legacy endpoint)
     */
    @GetMapping("/rightsizing/aws/xamops-recommendations")
    public ResponseEntity<List<XamOpsRightsizingRecommendation>> getXamOpsRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("⚡ Fetching XamOps rightsizing recommendations for account: {}", accountId);
        try {
            List<XamOpsRightsizingRecommendation> recommendations =
                    xamOpsRightsizingService.getLiveRecommendations(accountId, forceRefresh);

            logger.info("✅ Returning {} XamOps recommendations", recommendations.size());
            return ResponseEntity.ok(recommendations);
        } catch (Exception e) {
            logger.error("❌ Error fetching XamOps recommendations for account {}", accountId, e);
            return ResponseEntity.status(500).body(Collections.emptyList());
        }
    }

    /**
     * ✅ NEW: Get XamOps live recommendations (matches frontend endpoint)
     */
    @GetMapping("/optimization/rightsizing/live")
    public ResponseEntity<List<XamOpsRightsizingRecommendation>> getLiveRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("🚀 Fetching LIVE XamOps rightsizing recommendations for account: {}", accountId);
        try {
            List<XamOpsRightsizingRecommendation> recommendations =
                    xamOpsRightsizingService.getLiveRecommendations(accountId, forceRefresh);

            logger.info("✅ Returning {} live XamOps recommendations", recommendations.size());
            return ResponseEntity.ok(recommendations);
        } catch (Exception e) {
            logger.error("❌ Error fetching live XamOps recommendations for account {}", accountId, e);
            return ResponseEntity.status(500).body(Collections.emptyList());
        }
    }
}
