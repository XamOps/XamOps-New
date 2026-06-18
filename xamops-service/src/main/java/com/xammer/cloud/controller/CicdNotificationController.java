package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CicdNotificationConfig;
import com.xammer.cloud.dto.cicd.NotificationConfigDto;
import com.xammer.cloud.repository.CicdNotificationConfigRepository;
import com.xammer.cloud.service.CicdConfigurationService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping({"/api/cicd", "/cicd"})
@CrossOrigin(origins = "*")
public class CicdNotificationController {

    private final CicdNotificationConfigRepository configRepo;
    private final CicdConfigurationService cicdConfigService;

    public CicdNotificationController(CicdNotificationConfigRepository configRepo,
                                       CicdConfigurationService cicdConfigService) {
        this.configRepo = configRepo;
        this.cicdConfigService = cicdConfigService;
    }

    @GetMapping("/notification-config")
    public ResponseEntity<NotificationConfigDto> getConfig() {
        return cicdConfigService.getCurrentUser()
            .map(user -> {
                Optional<CicdNotificationConfig> cfg = configRepo.findByUserId(user.getId());
                NotificationConfigDto dto = new NotificationConfigDto();
                dto.setEmailEnabled(cfg.map(CicdNotificationConfig::isEmailEnabled).orElse(true));
                dto.setSlackWebhookUrl(cfg.map(CicdNotificationConfig::getSlackWebhookUrl).orElse(null));
                return ResponseEntity.ok(dto);
            })
            .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping("/notification-config")
    public ResponseEntity<Void> saveConfig(@RequestBody NotificationConfigDto dto) {
        return cicdConfigService.getCurrentUser()
            .map(user -> {
                CicdNotificationConfig cfg = configRepo.findByUserId(user.getId())
                        .orElseGet(() -> { CicdNotificationConfig c = new CicdNotificationConfig(); c.setUserId(user.getId()); return c; });
                cfg.setEmailEnabled(dto.isEmailEnabled());
                cfg.setSlackWebhookUrl(dto.getSlackWebhookUrl());
                cfg.setUpdatedAt(Instant.now());
                configRepo.save(cfg);
                return ResponseEntity.ok().<Void>build();
            })
            .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }
}
