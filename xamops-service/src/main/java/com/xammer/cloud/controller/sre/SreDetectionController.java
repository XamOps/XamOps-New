package com.xammer.cloud.controller.sre;

import com.xammer.cloud.dto.sre.DetectionEventDto;
import com.xammer.cloud.repository.DetectionEventRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.OpenObserveService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sre/detection")
public class SreDetectionController {

    private final DetectionEventRepository detectionRepo;
    private final ObservabilityConfigRepository obsConfigRepo;
    private final OpenObserveService openObserveService;

    public SreDetectionController(DetectionEventRepository detectionRepo,
                                  ObservabilityConfigRepository obsConfigRepo,
                                  OpenObserveService openObserveService) {
        this.detectionRepo = detectionRepo;
        this.obsConfigRepo = obsConfigRepo;
        this.openObserveService = openObserveService;
    }

    @GetMapping("/events")
    public ResponseEntity<List<DetectionEventDto>> getRecentEvents(
            @AuthenticationPrincipal ClientUserDetails user) {
        List<DetectionEventDto> dtos = detectionRepo
            .findTop10ByClientIdOrderByDetectedAtDesc(user.getClientId())
            .stream().map(DetectionEventDto::from).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyStream(
            @RequestParam String stream,
            @RequestParam String entity,
            @AuthenticationPrincipal ClientUserDetails user) {
        try {
            var obsConfig = obsConfigRepo.findByClientId(user.getClientId()).orElse(null);
            if (obsConfig == null) {
                return ResponseEntity.ok(Map.of("connected", false, "lastEventAt", ""));
            }
            long nowSec = System.currentTimeMillis() / 1000;
            Map<String, Object> params = Map.of(
                "providerAccountId", obsConfig.getO2OrgId(),
                "stream", stream,
                "keyword", entity,
                "startTime", nowSec - 86400,
                "endTime", nowSec,
                "size", 1
            );
            Map<String, Object> result = openObserveService.searchLogs(user.getClientId(), params);
            Object hits = result.get("hits");
            boolean connected = hits instanceof List<?> list && !list.isEmpty();
            String lastEventAt = "";
            if (connected && ((List<?>) hits).get(0) instanceof Map<?, ?> row) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rowMap = (Map<String, Object>) row;
                Object ts = rowMap.containsKey("_timestamp") ? rowMap.get("_timestamp") : rowMap.get("timestamp");
                if (ts != null) lastEventAt = String.valueOf(ts);
            }
            return ResponseEntity.ok(Map.of("connected", connected, "lastEventAt", lastEventAt));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("connected", false, "lastEventAt", ""));
        }
    }
}
