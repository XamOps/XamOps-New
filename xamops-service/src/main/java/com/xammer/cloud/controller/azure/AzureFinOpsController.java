package com.xammer.cloud.controller.azure;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.FinOpsReportScheduleDto;
import com.xammer.cloud.dto.azure.AzureFinOpsReportDto;
// --- FIX: Import UserRepository ---
import com.xammer.cloud.repository.UserRepository; 
import com.xammer.cloud.service.azure.AzureFinOpsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/azure")
@CrossOrigin(origins = "*") 
public class AzureFinOpsController {

    @Autowired
    private AzureFinOpsService azureFinOpsService;

    // --- FIX: Use UserRepository instead of UserService ---
    @Autowired
    private UserRepository userRepository; 

    /**
     * GET /api/azure/finops-report
     * Fetches the main FinOps report data for the given subscription.
     */
    @GetMapping("/finops-report")
    public ResponseEntity<AzureFinOpsReportDto> getFinOpsReport(@RequestParam String subscriptionId) {
        AzureFinOpsReportDto report = azureFinOpsService.getFinOpsReport(subscriptionId);
        return ResponseEntity.ok(report);
    }

    /**
     * GET /api/azure/finops-schedules
     * Lists all report schedules for the current user and subscription.
     */
    @GetMapping("/finops-schedules")
    public ResponseEntity<List<FinOpsReportScheduleDto>> getSchedules(@RequestParam String subscriptionId, Principal principal) {
        // --- FIX: Use userRepository ---
        User user = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        List<FinOpsReportScheduleDto> schedules = azureFinOpsService.getSchedules(subscriptionId, user);
        return ResponseEntity.ok(schedules);
    }

    /**
     * POST /api/azure/finops-schedules
     * Creates a new report schedule for the current user.
     * The DTO will be populated by the frontend. We must get the subscriptionId
     * from the DTO's 'cloudAccountId' field, which the frontend will set.
     */
    @PostMapping("/finops-schedules")
    public ResponseEntity<FinOpsReportScheduleDto> createSchedule(@RequestBody FinOpsReportScheduleDto scheduleDto, Principal principal) {
        // --- FIX: Use userRepository ---
        User user = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        FinOpsReportScheduleDto createdSchedule = azureFinOpsService.createSchedule(scheduleDto, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdSchedule);
    }

    /**
     * DELETE /api/azure/finops-schedules/{scheduleId}
     * Deletes a specific report schedule for the current user.
     */
    @DeleteMapping("/finops-schedules/{scheduleId}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable Long scheduleId, Principal principal) {
        // --- FIX: Use userRepository ---
        User user = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        azureFinOpsService.deleteSchedule(scheduleId, user);
        return ResponseEntity.noContent().build();
    }
}