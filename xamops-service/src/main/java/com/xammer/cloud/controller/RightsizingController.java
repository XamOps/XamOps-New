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
@RequestMapping("/api/xamops/rightsizing")
public class RightsizingController {

    private static final Logger logger = LoggerFactory.getLogger(RightsizingController.class);

    private final OptimizationService optimizationService;
    private final XamOpsRightsizingService xamOpsRightsizingService;

    public RightsizingController(OptimizationService optimizationService, XamOpsRightsizingService xamOpsRightsizingService) {
        this.optimizationService = optimizationService;
        this.xamOpsRightsizingService = xamOpsRightsizingService;
    }

    @GetMapping("/recommendations")
    public CompletableFuture<ResponseEntity<List<DashboardData.OptimizationRecommendation>>> getRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return optimizationService.getAllOptimizationRecommendations(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching optimization recommendations for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    /**
     * ✅ UPDATED: Now accepts accountId to fetch live instance data for targeted recommendations.
     */
    @GetMapping("/aws/xamops-recommendations")
    public List<XamOpsRightsizingRecommendation> getXamOpsRecommendations(@RequestParam String accountId) {
        return xamOpsRightsizingService.getLiveRecommendations(accountId);
    }
}