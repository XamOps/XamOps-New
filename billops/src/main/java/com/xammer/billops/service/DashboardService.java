package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.DashboardDataDto;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DashboardService {

    private final CostService costService;
    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    public DashboardService(CostService costService) {
        this.costService = costService;
    }

    // --- METHOD UPDATED to accept and pass year/month ---
    public DashboardDataDto getDashboardData(CloudAccount account, Integer year, Integer month) {
        CompletableFuture<List<Map<String, Object>>> costHistoryFuture = CompletableFuture.supplyAsync(() -> costService.getCostHistory(account), executor);
        CompletableFuture<Double> forecastFuture = CompletableFuture.supplyAsync(() -> costService.getForecastedSpend(account), executor);
        CompletableFuture<List<Map<String, Object>>> costByServiceFuture = CompletableFuture.supplyAsync(() -> costService.getCostByDimension(account, "SERVICE", year, month), executor);
        CompletableFuture<List<Map<String, Object>>> costByRegionFuture = CompletableFuture.supplyAsync(() -> costService.getCostByDimension(account, "REGION", year, month), executor);

        CompletableFuture.allOf(costHistoryFuture, costByServiceFuture, costByRegionFuture, forecastFuture).join();

        try {
            List<Map<String, Object>> costHistory = costHistoryFuture.get();
            List<Map<String, Object>> costByService = costByServiceFuture.get();

            double mtdSpend = costHistory.isEmpty() ? 0 : (double) costHistory.get(costHistory.size() - 1).get("cost");
            double lastMonthSpend = costHistory.size() < 2 ? 0 : (double) costHistory.get(costHistory.size() - 2).get("cost");

            return DashboardDataDto.builder()
                    .monthToDateSpend(mtdSpend)
                    .lastMonthSpend(lastMonthSpend)
                    .forecastedSpend(forecastFuture.get())
                    .costHistory(costHistory)
                    .costByService(costByService)
                    .costByRegion(costByRegionFuture.get())
                    .costBreakdown(costByService)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Error fetching dashboard data", e);
        }
    }
}