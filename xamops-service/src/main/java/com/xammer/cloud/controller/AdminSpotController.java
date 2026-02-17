package com.xammer.cloud.controller;

import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AdminSpotController
 *
 * Provides system-wide AutoSpotting metrics for the BillOps Admin Dashboard.
 */
@RestController
@RequestMapping("/api/admin/spot")
public class AdminSpotController {

    private final SpotOptimizationEventRepository eventRepository;
    private final DailySpotSavingsRepository savingsRepository;

    public AdminSpotController(SpotOptimizationEventRepository eventRepository,
            DailySpotSavingsRepository savingsRepository) {
        this.eventRepository = eventRepository;
        this.savingsRepository = savingsRepository;
    }

    /**
     * Returns top-level metrics for the Admin Dashboard.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getGlobalStats() {
        Map<String, Object> stats = new HashMap<>();

        Double totalSavings = savingsRepository.getTotalSavings();
        stats.put("totalSavings", totalSavings != null ? totalSavings : 0.0);

        List<SpotOptimizationEvent> recentActivity = eventRepository.findTop10ByOrderByEventTimeDesc();
        stats.put("recentActivity", recentActivity);

        return ResponseEntity.ok(stats);
    }

    /**
     * Returns historical savings for a specific account.
     */
    @GetMapping("/savings/history")
    public ResponseEntity<List<DailySpotSavings>> getAccountSavingsHistory(@RequestParam Long accountId) {
        LocalDate thirtyDaysAgo = LocalDate.now().minusDays(30);
        return ResponseEntity.ok(savingsRepository.findByAccountIdAndDateAfterOrderByDateAsc(accountId, thirtyDaysAgo));
    }
}
