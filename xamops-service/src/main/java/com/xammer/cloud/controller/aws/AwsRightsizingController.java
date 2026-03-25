package com.xammer.cloud.controller.aws;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.OptimizationService;
import com.xammer.cloud.service.RightsizingApplyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/xamops")
public class AwsRightsizingController {

    private static final Logger logger = LoggerFactory.getLogger(AwsRightsizingController.class);

    private final OptimizationService optimizationService;
    private final RightsizingApplyService rightsizingApplyService;

    public AwsRightsizingController(OptimizationService optimizationService,
                                     RightsizingApplyService rightsizingApplyService) {
        this.optimizationService = optimizationService;
        this.rightsizingApplyService = rightsizingApplyService;
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

    @PostMapping("/rightsizing/apply")
    public ResponseEntity<?> applyRightsizing(
            @RequestParam String accountId,
            @RequestParam String instanceId,
            @RequestParam String targetInstanceType,
            @RequestParam String region) {
        try {
            logger.info("Apply rightsizing request: instance={} targetType={} region={} account={}",
                    instanceId, targetInstanceType, region, accountId);
            rightsizingApplyService.applyEc2Rightsizing(accountId, instanceId, targetInstanceType, region);
            return ResponseEntity.accepted().body(Map.of(
                    "message", "Rightsizing is being applied. Instance " + instanceId +
                               " will be stopped, resized to " + targetInstanceType + ", and restarted.",
                    "instanceId", instanceId,
                    "targetInstanceType", targetInstanceType
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to apply rightsizing for instance {}: {}", instanceId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to apply rightsizing", "message", e.getMessage()));
        }
    }
}
