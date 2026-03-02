package com.xammer.cloud.controller.aws;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.OptimizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/xamops")
public class AwsRightsizingController {

    private static final Logger logger = LoggerFactory.getLogger(AwsRightsizingController.class);

    private final OptimizationService optimizationService;

    public AwsRightsizingController(OptimizationService optimizationService) {
        this.optimizationService = optimizationService;
    }

    @GetMapping("/rightsizing/recommendations")
    public ResponseEntity<List<DashboardData.OptimizationRecommendation>> getRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            List<DashboardData.OptimizationRecommendation> recommendations =
                    optimizationService.getAllOptimizationRecommendations(accountId, forceRefresh)
                            .get(50, TimeUnit.SECONDS);
            return ResponseEntity.ok(recommendations != null ? recommendations : Collections.emptyList());
        } catch (TimeoutException e) {
            logger.warn("Rightsizing recommendations timed out for account {}", accountId);
            return ResponseEntity.ok(Collections.emptyList());
        } catch (Exception e) {
            logger.error("Error fetching rightsizing recommendations for account {}", accountId, e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }
}
