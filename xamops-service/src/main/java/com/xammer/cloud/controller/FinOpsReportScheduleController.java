package com.xammer.cloud.controller;

import com.xammer.cloud.dto.FinOpsReportScheduleDto;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.FinOpsReportScheduleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
// --- MODIFICATION: Changed the URL path ---
@RequestMapping("/api/xamops/finops-schedules")
public class FinOpsReportScheduleController {

    private static final Logger logger = LoggerFactory.getLogger(FinOpsReportScheduleController.class);

    private final FinOpsReportScheduleService scheduleService;

    public FinOpsReportScheduleController(FinOpsReportScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PostMapping
    public ResponseEntity<FinOpsReportScheduleDto> createSchedule(
            @RequestBody FinOpsReportScheduleDto dto,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        try {
            FinOpsReportScheduleDto createdSchedule = scheduleService.createSchedule(dto, userDetails.getUsername());
            return ResponseEntity.status(HttpStatus.CREATED).body(createdSchedule);
        } catch (Exception e) {
            logger.error("Error creating FinOps schedule for user {}: {}", userDetails.getUsername(), e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping
    public ResponseEntity<List<FinOpsReportScheduleDto>> getSchedules(
            @RequestParam String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        try {
            List<FinOpsReportScheduleDto> schedules = scheduleService.getSchedulesForUserAndAccount(userDetails.getUsername(), accountId);
            return ResponseEntity.ok(schedules);
        } catch (Exception e) {
            logger.error("Error fetching schedules for user {} and account {}: {}", userDetails.getUsername(), accountId, e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteSchedule(
            @PathVariable Long id,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        try {
            scheduleService.deleteSchedule(id, userDetails.getUsername());
            return ResponseEntity.ok(Map.of("message", "Schedule deleted successfully"));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error deleting schedule ID {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}