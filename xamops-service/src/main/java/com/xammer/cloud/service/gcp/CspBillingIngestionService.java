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
import java.util.stream.Collectors;

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
        List<Long> accountIds = cloudAccountRepository.findByCspManagedTrue().stream()
            .map(CloudAccount::getId)
            .collect(Collectors.toList());
        for (Long accountId : accountIds) {
            try {
                ingestForAccountId(accountId);
            } catch (Exception e) {
                log.error("CSP ingestion failed for account id {}", accountId, e);
            }
        }
        log.info("CSP billing ingestion complete — {} accounts processed", accountIds.size());
    }

    /**
     * Ingest by id, loading the account inside the transaction.
     * <p>
     * {@link CloudAccount#getCspBillingConfig()} is a LAZY association, so an account
     * loaded elsewhere and passed in detached blows up with LazyInitializationException
     * outside a web request (the scheduled job, and onboarding). Re-reading it here keeps
     * the session open for the whole ingest.
     */
    @Transactional
    public void ingestForAccountId(Long accountId) {
        cloudAccountRepository.findById(accountId).ifPresentOrElse(
            this::ingestForAccount,
            () -> log.warn("CSP ingestion requested for unknown account id {}", accountId));
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

        // Month-to-date by USAGE DATE, not invoice_month.
        //
        // Two reasons. First, GCP bills usage sitting on a month boundary to the PREVIOUS
        // invoice period, so an invoice_month='YYYYMM' filter silently drops it — for
        // trustsignal-prod-320607 that was ~681 of early-August usage invoiced as 202607,
        // roughly 15% of the month. Second, GcpCostService's CSP read path already filters
        // on DATE(usage_start_time) and writes the SAME cache keys as this job. Two writers
        // with different filters made the dashboard KPI flip between two values depending on
        // which ran last. Matching the filter here keeps every page on one number.
        LocalDate today = LocalDate.now();
        String monthStart = today.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String monthEnd = today.format(DateTimeFormatter.ISO_LOCAL_DATE);

        BigQuery bq = queryService.buildClient(config);

        List<GcpCostDto> breakdown = queryService.queryBreakdownByDateRange(
            bq, config, projectId, "service_description", monthStart, monthEnd);
        writeCache("GCP_COST_BREAKDOWN::" + projectId + "::SERVICE", breakdown, 60);
        // The dashboard's Top Services widget reads the billing summary under its own key
        // (GcpCostService.BILLING_SUMMARY_CACHE_PREFIX), not the breakdown key. Same
        // service-level data, so publish it there too or CSP accounts show an empty widget.
        writeCache("gcp:billing-summary:" + projectId, breakdown, 60);

        List<GcpCostDto> regions = queryService.queryBreakdownByDateRange(
            bq, config, projectId, "region", monthStart, monthEnd);
        writeCache("GCP_COST_BY_REGION::" + projectId, regions, 60);

        List<GcpCostDto> history = queryService.queryMonthlyHistory(bq, config, projectId);
        writeCache("GCP_COST_HISTORY::" + projectId, history, 60 * 24);

        double mtd = queryService.queryTotalByDateRange(bq, config, projectId, monthStart, monthEnd);
        writeCache("gcp:unfiltered-mtd-spend:" + projectId, mtd, 60);

        boolean hasData = !breakdown.isEmpty() || !regions.isEmpty() || !history.isEmpty() || mtd > 0;
        account.setBillingDataAvailable(hasData);
        cloudAccountRepository.save(account);

        if (!hasData) {
            log.warn("CSP ingestion: no billing data found in dataset for project {} — project may not be in Sisl export yet", projectId);
        } else {
            log.info("CSP ingestion done for {}: {} services, {} regions, {} months",
                projectId, breakdown.size(), regions.size(), history.size());
        }
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
