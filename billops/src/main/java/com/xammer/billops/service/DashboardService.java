package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.DashboardCardDto;
import com.xammer.billops.dto.DashboardDataDto;
import com.xammer.billops.repository.CreditRequestRepository;
import com.xammer.billops.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);
    private final CostService costService;
    private final TicketRepository ticketRepository;
    private final CreditRequestRepository creditRequestRepository;
    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    public DashboardService(CostService costService, TicketRepository ticketRepository, CreditRequestRepository creditRequestRepository) {
        this.costService = costService;
        this.ticketRepository = ticketRepository;
        this.creditRequestRepository = creditRequestRepository;
    }

    public DashboardCardDto getDashboardCards() {
        long ticketsRaised = ticketRepository.count();
        long creditRequests = creditRequestRepository.count();
        // Placeholder values for spending
        return new DashboardCardDto(
                new BigDecimal("1234.56"),
                new BigDecimal("1100.00"),
                new BigDecimal("1500.00"),
                ticketsRaised,
                creditRequests
        );
    }

    @Cacheable(value = "dashboardData", key = "{#account.awsAccountId, #year, #month}")
    public DashboardDataDto getDashboardData(CloudAccount account, int year, int month) {
        logger.info("Fetching dashboard data for account: {}, period: {}-{}", account.getAwsAccountId(), year, month);

        CompletableFuture<List<Map<String, Object>>> costHistoryFuture = CompletableFuture.supplyAsync(() -> costService.getCostHistory(account, year, month), executor);
        CompletableFuture<List<Map<String, Object>>> costByServiceFuture = CompletableFuture.supplyAsync(() -> costService.getCostByDimension(account, "SERVICE", year, month), executor);
        CompletableFuture<List<Map<String, Object>>> costByRegionFuture = CompletableFuture.supplyAsync(() -> costService.getCostByDimension(account, "REGION", year, month), executor);

        CompletableFuture.allOf(costHistoryFuture, costByServiceFuture, costByRegionFuture).join();

        try {
            DashboardDataDto dto = new DashboardDataDto();
            List<Map<String, Object>> costHistory = costHistoryFuture.get();
            List<Map<String, Object>> costByService = costByServiceFuture.get();

            double mtdSpend = costHistory.isEmpty() ? 0 : (double) costHistory.get(costHistory.size() - 1).get("cost");
            double lastMonthSpend = costHistory.size() < 2 ? 0 : (double) costHistory.get(costHistory.size() - 2).get("cost");

            dto.setMonthToDateSpend(mtdSpend);
            dto.setLastMonthSpend(lastMonthSpend);
            dto.setForecastedSpend(mtdSpend * 1.1); // Placeholder forecast
            dto.setCostHistory(costHistory);
            dto.setCostByService(costByService);
            dto.setCostByRegion(costByRegionFuture.get());
            dto.setCostBreakdown(costByService);
            return dto;
        } catch (Exception e) {
            logger.error("Error assembling dashboard data for account {}: {}", account.getAwsAccountId(), e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new DashboardDataDto(); // Return empty DTO on error
        }
    }

    @CacheEvict(value = "dashboardData", allEntries = true)
    public void evictDashboardCache() {
        logger.info("Evicting all dashboard data caches.");
    }
}