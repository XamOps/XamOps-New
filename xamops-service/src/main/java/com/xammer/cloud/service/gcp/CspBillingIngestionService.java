package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.BigQuery;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CspBillingConfig;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CspBillingIngestionService {

    private final CloudAccountRepository cloudAccountRepository;
    private final CspBillingQueryService queryService;
    private final CachedDataRepository cachedDataRepository;
    private final RedisCacheService redisCacheService;
    private final ObjectMapper objectMapper;

    @Scheduled(cron = "0 0 2 * * *")
    public void ingestAll() {
        log.info("Starting daily CSP billing ingestion");
        List<CloudAccount> accounts = cloudAccountRepository.findByCspManagedTrue();
        for (CloudAccount account : accounts) {
            try {
                ingestForAccount(account);
            } catch (Exception e) {
                log.error("CSP ingestion failed for account {}", account.getGcpProjectId(), e);
            }
        }
        log.info("CSP billing ingestion complete — {} accounts processed", accounts.size());
    }

    @Transactional
    public void ingestForAccount(CloudAccount account) {
        CspBillingConfig config = account.getCspBillingConfig();
        if (config == null) {
            log.warn("Account {} is csp_managed but has no CspBillingConfig linked — skipping",
                account.getGcpProjectId());
            return;
        }

        String projectId = account.getGcpProjectId();
        String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));

        BigQuery bq = queryService.buildClient(config);

        List<GcpCostDto> breakdown = queryService.queryServiceBreakdown(bq, config, projectId, currentMonth);
        writeCache("GCP_COST_BREAKDOWN::" + projectId + "::SERVICE", breakdown, 60);

        List<GcpCostDto> regions = queryService.queryRegionBreakdown(bq, config, projectId, currentMonth);
        writeCache("GCP_COST_BY_REGION::" + projectId, regions, 60);

        List<GcpCostDto> history = queryService.queryMonthlyHistory(bq, config, projectId);
        writeCache("GCP_COST_HISTORY::" + projectId, history, 60 * 24);

        double mtd = queryService.queryMtdTotal(bq, config, projectId, currentMonth);
        writeCache("gcp:unfiltered-mtd-spend:" + projectId, mtd, 60);

        log.info("CSP ingestion done for {}: {} services, {} regions, {} months",
            projectId, breakdown.size(), regions.size(), history.size());
    }

    private void writeCache(String key, Object value, int ttlMinutes) {
        try {
            String json = objectMapper.writeValueAsString(value);
            cachedDataRepository.save(new CachedData(key, json, LocalDateTime.now()));
            redisCacheService.put(key, value, ttlMinutes);
        } catch (Exception e) {
            log.error("Failed to write cache key {}", key, e);
        }
    }
}
