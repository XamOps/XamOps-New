package com.xammer.cloud.service;

import com.xammer.cloud.dto.AIOPsDashboardDto.*;
import com.xammer.cloud.repository.LlmUsageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AIOpsInsightsService {

    private static final Logger logger = LoggerFactory.getLogger(AIOpsInsightsService.class);

    private final LlmUsageLogRepository usageLogRepo;
    private final AIOpsService aiOpsService;

    public AIOpsInsightsService(LlmUsageLogRepository usageLogRepo, AIOpsService aiOpsService) {
        this.usageLogRepo = usageLogRepo;
        this.aiOpsService = aiOpsService;
    }

    public InsightsSummary analyzeUsage(Long clientId, String period) {
        List<InsightCard> insights = new ArrayList<>();
        double totalEstimatedSavings = 0;

        try {
            Instant since = aiOpsService.parsePeriod(period);

            // 1. Cost Optimization: Compare models used by the same agent
            totalEstimatedSavings += generateCostOptimizationInsights(clientId, since, insights);

            // 2. Usage Patterns: Peak hours/days
            generateUsagePatternInsights(clientId, since, insights);

            // 3. Anomaly Summaries
            generateAnomalyInsights(clientId, since, insights);

            // 4. Performance Recommendations
            generatePerformanceInsights(clientId, since, insights);

            // 5. Trend: current vs previous period
            generateTrendInsights(clientId, period, since, insights);

        } catch (Exception e) {
            logger.error("Error generating insights for client {}: {}", clientId, e.getMessage(), e);
        }

        int actionableCount = (int) insights.stream().filter(InsightCard::isActionable).count();

        return new InsightsSummary(
                insights,
                insights.size(),
                actionableCount,
                totalEstimatedSavings,
                Instant.now().toString()
        );
    }

    // ─── Cost Optimization ───────────────────────────────────────

    private double generateCostOptimizationInsights(Long clientId, Instant since, List<InsightCard> insights) {
        double savings = 0;
        try {
            // Get model comparison data to find expensive models
            List<Object[]> modelRows = usageLogRepo.modelComparisonStatsSince(clientId, since);
            if (modelRows.size() < 2) return 0;

            // Group by provider and find cheaper alternatives
            Map<String, List<Object[]>> byProvider = new LinkedHashMap<>();
            for (Object[] row : modelRows) {
                String provider = (String) row[1];
                byProvider.computeIfAbsent(provider, k -> new ArrayList<>()).add(row);
            }

            for (Map.Entry<String, List<Object[]>> entry : byProvider.entrySet()) {
                List<Object[]> models = entry.getValue();
                if (models.size() < 2) continue;

                // Sort by cost per request (cost / requests) descending
                models.sort((a, b) -> {
                    double costPerReqA = ((Number) a[5]).doubleValue() / Math.max(1, ((Number) a[2]).longValue());
                    double costPerReqB = ((Number) b[5]).doubleValue() / Math.max(1, ((Number) b[2]).longValue());
                    return Double.compare(costPerReqB, costPerReqA);
                });

                Object[] expensive = models.get(0);
                Object[] cheap = models.get(models.size() - 1);

                String expModel = (String) expensive[0];
                String cheapModel = (String) cheap[0];
                double expCost = ((Number) expensive[5]).doubleValue();
                long expRequests = ((Number) expensive[2]).longValue();
                double cheapCostPerReq = ((Number) cheap[5]).doubleValue() / Math.max(1, ((Number) cheap[2]).longValue());

                if (expRequests > 0 && !expModel.equals(cheapModel)) {
                    double potentialCost = cheapCostPerReq * expRequests;
                    double potentialSavings = expCost - potentialCost;
                    if (potentialSavings > 0.001) {
                        savings += potentialSavings;
                        insights.add(new InsightCard(
                                UUID.randomUUID().toString(),
                                "COST_OPTIMIZATION",
                                "ACTIONABLE",
                                "Switch from " + truncateModel(expModel) + " to " + truncateModel(cheapModel),
                                String.format("You spent $%.4f on %s (%d requests). Using %s for the same workload would cost ~$%.4f.",
                                        expCost, truncateModel(expModel), expRequests, truncateModel(cheapModel), potentialCost),
                                String.format("Save ~$%.4f", potentialSavings),
                                true,
                                "Review Models",
                                Map.of("sourceModel", expModel, "targetModel", cheapModel, "provider", entry.getKey())
                        ));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error generating cost optimization insights: {}", e.getMessage());
        }
        return savings;
    }

    // ─── Usage Patterns ──────────────────────────────────────────

    private void generateUsagePatternInsights(Long clientId, Instant since, List<InsightCard> insights) {
        try {
            List<Object[]> heatmapRows = usageLogRepo.tokenHeatmapSince(clientId, since);
            if (heatmapRows.isEmpty()) return;

            // Find peak hour
            long maxTokens = 0;
            int peakDow = 0, peakHour = 0;
            long totalTokens = 0;
            for (Object[] r : heatmapRows) {
                long tokens = ((Number) r[2]).longValue();
                totalTokens += tokens;
                if (tokens > maxTokens) {
                    maxTokens = tokens;
                    peakDow = ((Number) r[0]).intValue();
                    peakHour = ((Number) r[1]).intValue();
                }
            }

            if (maxTokens > 0) {
                String[] days = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
                String dayName = peakDow >= 0 && peakDow < days.length ? days[peakDow] : "Day " + peakDow;
                String hourStr = String.format("%d:00-%d:00", peakHour, (peakHour + 1) % 24);
                double pctOfTotal = totalTokens > 0 ? (double) maxTokens / totalTokens * 100 : 0;

                insights.add(new InsightCard(
                        UUID.randomUUID().toString(),
                        "USAGE_PATTERN",
                        "INFO",
                        "Peak usage: " + dayName + " " + hourStr,
                        String.format("Your highest token usage occurs on %s at %s, accounting for %.1f%% of total tokens. Consider scheduling batch jobs outside peak hours.",
                                dayName, hourStr, pctOfTotal),
                        null,
                        false,
                        null,
                        Map.of("peakDay", dayName, "peakHour", peakHour)
                ));
            }
        } catch (Exception e) {
            logger.warn("Error generating usage pattern insights: {}", e.getMessage());
        }
    }

    // ─── Anomaly Summaries ───────────────────────────────────────

    private void generateAnomalyInsights(Long clientId, Instant since, List<InsightCard> insights) {
        try {
            List<CostAnomaly> anomalies = aiOpsService.getAnomalies(clientId, "30d");
            if (anomalies.isEmpty()) {
                insights.add(new InsightCard(
                        UUID.randomUUID().toString(),
                        "ANOMALY",
                        "INFO",
                        "No cost anomalies detected",
                        "Your spending patterns are consistent. No unexpected cost spikes were detected in the current period.",
                        null,
                        false,
                        null,
                        Map.of()
                ));
                return;
            }

            long criticalCount = anomalies.stream().filter(a -> "CRITICAL".equals(a.getSeverity())).count();
            double totalDeviation = anomalies.stream().mapToDouble(a -> a.getActualCost() - a.getExpectedCost()).sum();

            insights.add(new InsightCard(
                    UUID.randomUUID().toString(),
                    "ANOMALY",
                    criticalCount > 0 ? "WARNING" : "INFO",
                    anomalies.size() + " cost anomal" + (anomalies.size() == 1 ? "y" : "ies") + " detected",
                    String.format("%d anomalies found (%d critical). Total excess spend: $%.4f above expected levels. Review your provider usage for unexpected spikes.",
                            anomalies.size(), criticalCount, totalDeviation),
                    criticalCount > 0 ? "Excess: $" + String.format("%.4f", totalDeviation) : null,
                    criticalCount > 0,
                    criticalCount > 0 ? "View Anomalies" : null,
                    Map.of("anomalyCount", anomalies.size(), "criticalCount", criticalCount)
            ));
        } catch (Exception e) {
            logger.warn("Error generating anomaly insights: {}", e.getMessage());
        }
    }

    // ─── Performance Recommendations ─────────────────────────────

    private void generatePerformanceInsights(Long clientId, Instant since, List<InsightCard> insights) {
        try {
            List<Object[]> relRows = usageLogRepo.modelReliabilityStats(clientId, since);
            if (relRows.size() < 2) return;

            // Find models with high error rates
            for (Object[] row : relRows) {
                String model = (String) row[0];
                long total = ((Number) row[2]).longValue();
                long errors = ((Number) row[3]).longValue();
                double errRate = total > 0 ? (double) errors / total * 100 : 0;

                if (errRate > 10 && total >= 5) {
                    insights.add(new InsightCard(
                            UUID.randomUUID().toString(),
                            "PERFORMANCE",
                            "WARNING",
                            truncateModel(model) + " has " + String.format("%.0f%%", errRate) + " error rate",
                            String.format("%s failed %d out of %d requests (%.1f%% error rate). Consider checking your API configuration or switching to a more reliable model.",
                                    truncateModel(model), errors, total, errRate),
                            "Reliability concern",
                            true,
                            "Check Model",
                            Map.of("model", model, "errorRate", errRate)
                    ));
                }
            }

            // Find slowest model vs fastest
            relRows.sort((a, b) -> Double.compare(((Number) b[4]).doubleValue(), ((Number) a[4]).doubleValue()));
            if (relRows.size() >= 2) {
                Object[] slowest = relRows.get(0);
                Object[] fastest = relRows.get(relRows.size() - 1);
                double slowLat = ((Number) slowest[4]).doubleValue();
                double fastLat = ((Number) fastest[4]).doubleValue();

                if (fastLat > 0 && slowLat / fastLat >= 2.0) {
                    insights.add(new InsightCard(
                            UUID.randomUUID().toString(),
                            "PERFORMANCE",
                            "INFO",
                            truncateModel((String) slowest[0]) + " is " + String.format("%.0fx", slowLat / fastLat) + " slower",
                            String.format("%s averages %.0fms latency vs %s at %.0fms. For latency-sensitive tasks, consider the faster model.",
                                    truncateModel((String) slowest[0]), slowLat, truncateModel((String) fastest[0]), fastLat),
                            null,
                            false,
                            null,
                            Map.of("slowModel", slowest[0], "fastModel", fastest[0])
                    ));
                }
            }
        } catch (Exception e) {
            logger.warn("Error generating performance insights: {}", e.getMessage());
        }
    }

    // ─── Trend Insights ──────────────────────────────────────────

    private void generateTrendInsights(Long clientId, String period, Instant since, List<InsightCard> insights) {
        try {
            // Compare current period to the same duration before
            long daysBetween = ChronoUnit.DAYS.between(since, Instant.now());
            if (daysBetween <= 0) daysBetween = 1;
            Instant previousStart = since.minus(daysBetween, ChronoUnit.DAYS);

            Double currentSpend = usageLogRepo.sumCostSince(clientId, since);
            Double previousSpend = usageLogRepo.sumCostSince(clientId, previousStart);

            // Subtract current from previous (previous includes current period in sum)
            // Actually sumCostSince sums from `since` to now, so we need a range query
            // For simplicity, previousSpend covers the wider window; subtract current
            double prevPeriodSpend = (previousSpend != null ? previousSpend : 0) - (currentSpend != null ? currentSpend : 0);
            double currPeriodSpend = currentSpend != null ? currentSpend : 0;

            if (prevPeriodSpend > 0) {
                double changePct = ((currPeriodSpend - prevPeriodSpend) / prevPeriodSpend) * 100;

                String direction = changePct >= 0 ? "increased" : "decreased";
                String severity = changePct > 50 ? "WARNING" : "INFO";

                insights.add(new InsightCard(
                        UUID.randomUUID().toString(),
                        "TREND",
                        severity,
                        String.format("Spending %s %.0f%% vs previous period", direction, Math.abs(changePct)),
                        String.format("Current period spend: $%.4f vs previous period: $%.4f (%s%.0f%%). %s",
                                currPeriodSpend, prevPeriodSpend,
                                changePct >= 0 ? "+" : "", changePct,
                                changePct > 50 ? "Investigate the spending increase." : "Spending is within normal ranges."),
                        String.format("$%.4f → $%.4f", prevPeriodSpend, currPeriodSpend),
                        changePct > 50,
                        changePct > 50 ? "Review Spending" : null,
                        Map.of("currentSpend", currPeriodSpend, "previousSpend", prevPeriodSpend, "changePct", changePct)
                ));
            } else if (currPeriodSpend > 0) {
                insights.add(new InsightCard(
                        UUID.randomUUID().toString(),
                        "TREND",
                        "INFO",
                        "First period with LLM usage",
                        String.format("You've spent $%.4f in this period. This is your first tracked period — future insights will compare against this baseline.", currPeriodSpend),
                        null,
                        false,
                        null,
                        Map.of()
                ));
            }
        } catch (Exception e) {
            logger.warn("Error generating trend insights: {}", e.getMessage());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private String truncateModel(String model) {
        if (model == null) return "unknown";
        return model.length() > 30 ? model.substring(0, 27) + "..." : model;
    }
}
