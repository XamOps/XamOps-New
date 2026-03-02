// package com.xammer.cloud.controller;

// import com.xammer.cloud.dto.DashboardData;
// import com.xammer.cloud.service.OptimizationService;
// import lombok.extern.slf4j.Slf4j;
// import org.springframework.http.ResponseEntity;
// import org.springframework.web.bind.annotation.*;

// import java.util.Collections;
// import java.util.List;

// @RestController
// @RequestMapping("/api/xamops/rightsizing")
// @Slf4j
// public class AwsRightsizingController {

//     private final OptimizationService optimizationService;

//     public AwsRightsizingController(OptimizationService optimizationService) {
//         this.optimizationService = optimizationService;
//     }

//     @GetMapping("/recommendations")
//     public ResponseEntity<List<DashboardData.OptimizationRecommendation>> getRecommendations(
//             @RequestParam String accountId,
//             @RequestParam(defaultValue = "false") boolean forceRefresh) {
//         try {
//             log.info("AWS Rightsizing request - Account: {} (forceRefresh={})", accountId, forceRefresh);
//             List<DashboardData.OptimizationRecommendation> recs =
//                     optimizationService.getAllOptimizationRecommendations(accountId, forceRefresh).join();
//             return ResponseEntity.ok(recs);
//         } catch (Exception e) {
//             log.error("Failed to fetch AWS rightsizing recommendations for {}: {}", accountId, e.getMessage(), e);
//             return ResponseEntity.ok(Collections.emptyList());
//         }
//     }
// }
