package com.xammer.cloud.service;

import com.xammer.cloud.domain.CicdRunRecord;
import com.xammer.cloud.dto.cicd.CicdDailyStatDto;
import com.xammer.cloud.dto.cicd.CicdDoraDto;
import com.xammer.cloud.dto.cicd.CicdMetricsDto;
import com.xammer.cloud.repository.CicdRunRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CicdMetricsService {

    private static final Logger logger = LoggerFactory.getLogger(CicdMetricsService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final CicdRunRecordRepository repository;

    public CicdMetricsService(CicdRunRecordRepository repository) {
        this.repository = repository;
    }

    public CicdMetricsDto computeMetrics(Long userId, String provider, int days) {
        Instant now = Instant.now();
        Instant periodStart = now.minus(days, ChronoUnit.DAYS);
        Instant prevPeriodStart = now.minus(days * 2L, ChronoUnit.DAYS);

        List<CicdRunRecord> current = repository
                .findByUserIdAndProviderAndStartedAtAfterOrderByStartedAtDesc(userId, provider, periodStart);

        List<CicdRunRecord> previous = repository
                .findByUserIdAndProviderAndStartedAtAfterOrderByStartedAtDesc(userId, provider, prevPeriodStart)
                .stream()
                .filter(r -> r.getStartedAt().isBefore(periodStart))
                .collect(Collectors.toList());

        CicdMetricsDto dto = new CicdMetricsDto();
        populateKpis(dto, current, false);
        populateKpis(dto, previous, true);
        dto.setDailyStats(buildDailyStats(current));
        dto.setTopFailingStages(buildTopFailingStages(current));
        dto.setTopFailingWorkflows(buildTopFailingWorkflows(current));

        if ("GITHUB".equals(provider)) {
            dto.setDora(buildDora(current, days));
        }

        return dto;
    }

    private void populateKpis(CicdMetricsDto dto, List<CicdRunRecord> runs, boolean prev) {
        int total = runs.size();
        int failures = (int) runs.stream().filter(r -> "failure".equals(r.getStatus())).count();
        double rate = total == 0 ? 0.0 : Math.round(((double)(total - failures) / total) * 10000.0) / 100.0;
        long avgDur = (long) runs.stream()
                .filter(r -> r.getDurationSeconds() != null)
                .mapToLong(CicdRunRecord::getDurationSeconds)
                .average()
                .orElse(0);

        if (!prev) {
            dto.setTotalRuns(total);
            dto.setFailureCount(failures);
            dto.setSuccessRate(rate);
            dto.setAvgDurationSeconds(avgDur);
        } else {
            dto.setPrevTotalRuns(total);
            dto.setPrevFailureCount(failures);
            dto.setPrevSuccessRate(rate);
            dto.setPrevAvgDurationSeconds(avgDur);
        }
    }

    private List<CicdDailyStatDto> buildDailyStats(List<CicdRunRecord> runs) {
        Map<String, int[]> byDay = new LinkedHashMap<>();
        for (CicdRunRecord r : runs) {
            String date = DATE_FMT.format(r.getStartedAt());
            byDay.computeIfAbsent(date, k -> new int[4]);
            int[] counts = byDay.get(date);
            if ("success".equals(r.getStatus())) counts[0]++;
            else if ("failure".equals(r.getStatus())) counts[1]++;
            if (r.getDurationSeconds() != null) {
                counts[2] += r.getDurationSeconds();
                counts[3]++;
            }
        }
        List<CicdDailyStatDto> stats = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byDay.entrySet()) {
            int[] c = e.getValue();
            long avg = c[3] == 0 ? 0 : c[2] / c[3];
            stats.add(new CicdDailyStatDto(e.getKey(), c[0], c[1], avg));
        }
        stats.sort(Comparator.comparing(CicdDailyStatDto::getDate));
        return stats;
    }

    private List<CicdMetricsDto.FailingStageDto> buildTopFailingStages(List<CicdRunRecord> runs) {
        Map<String, Integer> stageCounts = new HashMap<>();
        for (CicdRunRecord r : runs) {
            if ("failure".equals(r.getStatus()) && r.getFailedStage() != null) {
                stageCounts.merge(r.getFailedStage(), 1, Integer::sum);
            }
        }
        return stageCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(e -> new CicdMetricsDto.FailingStageDto(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private List<CicdMetricsDto.FailingWorkflowDto> buildTopFailingWorkflows(List<CicdRunRecord> runs) {
        Map<String, int[]> byWorkflow = new HashMap<>();
        for (CicdRunRecord r : runs) {
            byWorkflow.computeIfAbsent(r.getWorkflowName(), k -> new int[2]);
            int[] c = byWorkflow.get(r.getWorkflowName());
            c[0]++;
            if ("failure".equals(r.getStatus())) c[1]++;
        }
        return byWorkflow.entrySet().stream()
                .filter(e -> e.getValue()[1] > 0)
                .sorted((a, b) -> Integer.compare(b.getValue()[1], a.getValue()[1]))
                .limit(5)
                .map(e -> {
                    int total = e.getValue()[0];
                    int failures = e.getValue()[1];
                    double rate = total == 0 ? 0 : Math.round(((double)(total - failures) / total) * 10000.0) / 100.0;
                    return new CicdMetricsDto.FailingWorkflowDto(e.getKey(), failures, rate);
                })
                .collect(Collectors.toList());
    }

    private CicdDoraDto buildDora(List<CicdRunRecord> runs, int days) {
        long deployCount = runs.stream().filter(CicdRunRecord::isDeployment).count();
        double deployFreq = days == 0 ? 0 : Math.round((double) deployCount / days * 100.0) / 100.0;
        long mttr = computeMttr(runs);
        double cfr = computeChangeFailureRate(runs);
        return new CicdDoraDto(deployFreq, mttr, cfr);
    }

    // Package-visible for testing
    long computeMttr(List<CicdRunRecord> runs) {
        Map<String, List<CicdRunRecord>> byWorkflow = runs.stream()
                .collect(Collectors.groupingBy(CicdRunRecord::getWorkflowName));
        List<Long> recoveryTimes = new ArrayList<>();
        for (List<CicdRunRecord> wRuns : byWorkflow.values()) {
            wRuns.sort(Comparator.comparing(CicdRunRecord::getStartedAt));
            for (int i = 0; i < wRuns.size() - 1; i++) {
                if ("failure".equals(wRuns.get(i).getStatus()) && "success".equals(wRuns.get(i + 1).getStatus())) {
                    long secs = ChronoUnit.SECONDS.between(wRuns.get(i).getStartedAt(), wRuns.get(i + 1).getStartedAt());
                    recoveryTimes.add(secs);
                }
            }
        }
        return (long) recoveryTimes.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    // Package-visible for testing
    double computeChangeFailureRate(List<CicdRunRecord> runs) {
        long totalDeploys = runs.stream().filter(CicdRunRecord::isDeployment).count();
        long failedDeploys = runs.stream()
                .filter(r -> r.isDeployment() && "failure".equals(r.getStatus()))
                .count();
        return totalDeploys == 0 ? 0 : Math.round(((double) failedDeploys / totalDeploys) * 10000.0) / 100.0;
    }
}
