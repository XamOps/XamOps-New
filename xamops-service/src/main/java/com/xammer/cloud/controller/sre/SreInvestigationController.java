package com.xammer.cloud.controller.sre;

import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.domain.SreNotification;
import com.xammer.cloud.dto.sre.SreInvestigationDto;
import com.xammer.cloud.dto.sre.SreNotificationDto;
import com.xammer.cloud.dto.sre.TriggerInvestigationRequest;
import com.xammer.cloud.repository.SreNotificationRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.sre.SreInvestigationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sre")
public class SreInvestigationController {

    private final SreInvestigationService investigationService;
    private final SreNotificationRepository notificationRepo;

    public SreInvestigationController(SreInvestigationService investigationService,
                                      SreNotificationRepository notificationRepo) {
        this.investigationService = investigationService;
        this.notificationRepo = notificationRepo;
    }

    @PostMapping("/investigations")
    public ResponseEntity<SreInvestigationDto> triggerInvestigation(
            @RequestBody TriggerInvestigationRequest req,
            @AuthenticationPrincipal ClientUserDetails user) {
        SreInvestigation inv = investigationService.createInvestigation(
            user.getClientId(), req, SreInvestigation.TriggerType.MANUAL);
        return ResponseEntity.ok(SreInvestigationDto.from(inv));
    }

    @GetMapping("/investigations")
    public ResponseEntity<List<SreInvestigationDto>> listInvestigations(
            @AuthenticationPrincipal ClientUserDetails user) {
        List<SreInvestigationDto> dtos = investigationService.listForClient(user.getClientId())
            .stream().map(SreInvestigationDto::from).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/investigations/{id}")
    public ResponseEntity<SreInvestigationDto> getInvestigation(
            @PathVariable Long id,
            @AuthenticationPrincipal ClientUserDetails user) {
        return investigationService.getById(id, user.getClientId())
            .map(inv -> ResponseEntity.ok(SreInvestigationDto.from(inv)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/investigations/{id}/status")
    public ResponseEntity<Map<String, String>> getStatus(
            @PathVariable Long id,
            @AuthenticationPrincipal ClientUserDetails user) {
        return investigationService.getById(id, user.getClientId())
            .map(inv -> ResponseEntity.ok(Map.of("status", inv.getStatus().name())))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/notifications")
    public ResponseEntity<List<SreNotificationDto>> getNotifications(
            @AuthenticationPrincipal ClientUserDetails user) {
        List<SreNotificationDto> dtos = notificationRepo
            .findByClientIdAndReadFalseOrderByCreatedAtDesc(user.getClientId())
            .stream().map(SreNotificationDto::from).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/notifications/count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            @AuthenticationPrincipal ClientUserDetails user) {
        long count = notificationRepo.countByClientIdAndReadFalse(user.getClientId());
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PutMapping("/notifications/{id}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable Long id,
            @AuthenticationPrincipal ClientUserDetails user) {
        notificationRepo.findById(id).ifPresent(n -> {
            if (n.getClientId().equals(user.getClientId())) {
                n.setRead(true);
                notificationRepo.save(n);
            }
        });
        return ResponseEntity.ok().build();
    }
}
