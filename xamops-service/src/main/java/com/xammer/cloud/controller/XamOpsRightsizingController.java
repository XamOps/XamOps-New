package com.xammer.cloud.controller;

import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import com.xammer.cloud.service.XamOpsRightsizingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/xamops/optimization/rightsizing")
@Slf4j
public class XamOpsRightsizingController {

    private final XamOpsRightsizingService xamOpsRightsizingService;

    public XamOpsRightsizingController(XamOpsRightsizingService xamOpsRightsizingService) {
        this.xamOpsRightsizingService = xamOpsRightsizingService;
    }

    @GetMapping("/live")
    public ResponseEntity<List<XamOpsRightsizingRecommendation>> getLiveRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            log.info("XamOps Rightsizing request - Account: {} (forceRefresh={})", accountId, forceRefresh);
            List<XamOpsRightsizingRecommendation> recs =
                    xamOpsRightsizingService.getLiveRecommendations(accountId, forceRefresh);
            return ResponseEntity.ok(recs);
        } catch (Exception e) {
            log.error("Failed to fetch XamOps rightsizing recommendations for {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }
}
