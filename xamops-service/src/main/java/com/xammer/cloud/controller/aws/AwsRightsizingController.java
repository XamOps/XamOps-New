package com.xammer.cloud.controller.aws;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import com.xammer.cloud.service.OptimizationService;
import com.xammer.cloud.service.XamOpsRightsizingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/xamops")
public class AwsRightsizingController {

    private static final Logger logger = LoggerFactory.getLogger(AwsRightsizingController.class);

    private final OptimizationService optimizationService;
    private final XamOpsRightsizingService xamOpsRightsizingService;
    private final Executor taskExecutor;

    public AwsRightsizingController(OptimizationService optimizationService,
                                     XamOpsRightsizingService xamOpsRightsizingService,
                                     @Qualifier("awsTaskExecutor") Executor taskExecutor) {
        this.optimizationService = optimizationService;
        this.xamOpsRightsizingService = xamOpsRightsizingService;
        this.taskExecutor = taskExecutor;
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

    @GetMapping("/optimization/rightsizing/live")
    public ResponseEntity<List<XamOpsRightsizingRecommendation>> getLiveRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            List<XamOpsRightsizingRecommendation> recommendations =
                    CompletableFuture.supplyAsync(
                                    () -> xamOpsRightsizingService.getLiveRecommendations(accountId, forceRefresh),
                                    taskExecutor)
                            .get(50, TimeUnit.SECONDS);
            return ResponseEntity.ok(recommendations != null ? recommendations : Collections.emptyList());
        } catch (TimeoutException e) {
            logger.warn("Live rightsizing timed out for account {}", accountId);
            return ResponseEntity.ok(Collections.emptyList());
        } catch (Exception e) {
            logger.error("Error fetching live rightsizing for account {}", accountId, e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }
}
