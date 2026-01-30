package com.xammer.cloud.controller.gcp;

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

/**
 * Controller for managing GCP FinOps report schedules.
 * Uses the same underlying service as AWS since schedules are provider-agnostic.
 */
@RestController
@RequestMapping("/api/xamops/gcp/finops-schedules")
public class GcpFinOpsReportScheduleController {

    private static final Logger logger = LoggerFactory.getLogger(GcpFinOpsReportScheduleController.class);

    private final FinOpsReportScheduleService scheduleService;

    public GcpFinOpsReportScheduleController(FinOpsReportScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /**
     * Create a new FinOps report schedule for a GCP account.
     */
    @PostMapping
    public ResponseEntity<FinOpsReportScheduleDto> createSchedule(
            @RequestBody FinOpsReportScheduleDto dto,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            logger.info("Creating GCP FinOps schedule for user {} on account {}",
                    userDetails.getUsername(), dto.getCloudAccountId());
            FinOpsReportScheduleDto createdSchedule = scheduleService.createSchedule(dto, userDetails.getUsername());
            return ResponseEntity.status(HttpStatus.CREATED).body(createdSchedule);
        } catch (Exception e) {
            logger.error("Error creating GCP FinOps schedule for user {}: {}",
                    userDetails.getUsername(), e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get all schedules for the authenticated user and specified GCP account.
     */
    @GetMapping
    public ResponseEntity<List<FinOpsReportScheduleDto>> getSchedules(
            @RequestParam String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            List<FinOpsReportScheduleDto> schedules = scheduleService.getSchedulesForUserAndAccount(
                    userDetails.getUsername(), accountId);
            return ResponseEntity.ok(schedules);
        } catch (Exception e) {
            logger.error("Error fetching GCP schedules for user {} and account {}: {}",
                    userDetails.getUsername(), accountId, e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a schedule by ID.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteSchedule(
            @PathVariable Long id,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            scheduleService.deleteSchedule(id, userDetails.getUsername());
            logger.info("Deleted GCP FinOps schedule ID {} by user {}", id, userDetails.getUsername());
            return ResponseEntity.ok(Map.of("message", "Schedule deleted successfully"));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error deleting GCP schedule ID {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}
