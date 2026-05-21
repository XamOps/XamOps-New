package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentDataCache;
import com.xammer.cloud.domain.DbHealthSnapshot;
import com.xammer.cloud.dto.dbops.AgentDiagnosticDto;
import com.xammer.cloud.dto.dbops.AgentInstanceMetricsDto;
import com.xammer.cloud.dto.dbops.DbMetricsDto;
import com.xammer.cloud.dto.dbops.SlowQueriesResponseDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class DbHealthScheduler {

    private static final Logger logger = LoggerFactory.getLogger(DbHealthScheduler.class);

    private final CloudAccountRepository     cloudAccountRepository;
    private final DbOpsCostService           dbOpsCostService;
    private final DbHealthScoringService     scoringService;
    private final DbHealthAlertService       alertService;
    private final DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private final ObjectMapper               objectMapper;

    public DbHealthScheduler(CloudAccountRepository cloudAccountRepository,
                             DbOpsCostService dbOpsCostService,
                             DbHealthScoringService scoringService,
                             DbHealthAlertService alertService,
                             DbAgentDataCacheRepository dbAgentDataCacheRepository,
                             ObjectMapper objectMapper) {
        this.cloudAccountRepository  = cloudAccountRepository;
        this.dbOpsCostService        = dbOpsCostService;
        this.scoringService          = scoringService;
        this.alertService            = alertService;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.objectMapper            = objectMapper;
    }

    @Scheduled(fixedRate = 600_000)
    public void runHealthCycle() {
        List<CloudAccount> accounts = cloudAccountRepository.findAll();
        logger.info("DbHealthScheduler: cycle starting for {} accounts", accounts.size());
        for (CloudAccount account : accounts) {
            try { processAccount(account); }
            catch (Exception e) { logger.warn("DbHealthScheduler: failed for {}: {}", account.getId(), e.getMessage()); }
        }
        scoringService.purgeOldSnapshots();
    }

    private void processAccount(CloudAccount account) throws Exception {
        String accountId = String.valueOf(account.getId());
        List<DbHealthSnapshot> snapshots = scoringService.scoreAccount(account);
        if (snapshots.isEmpty()) return;

        Map<String, DbMetricsDto> metrics = dbOpsCostService
            .getDbMetrics(accountId, false).get(10, TimeUnit.SECONDS);

        SlowQueriesResponseDto slowResp = dbOpsCostService
            .getSlowQueries(accountId, false).get(10, TimeUnit.SECONDS);
        int slowCount = slowResp != null && slowResp.getQueries() != null ? slowResp.getQueries().size() : 0;

        List<AgentDiagnosticDto> diags = scoringService.resolveDiagnostics(account.getId());

        for (DbHealthSnapshot snap : snapshots) {
            DbMetricsDto m = metrics != null ? metrics.get(snap.getInstanceId()) : null;
            AgentInstanceMetricsDto am = resolveAgentMetrics(account.getId(), snap.getInstanceId());
            alertService.evaluate(account, snap, m, am, slowCount, diags);
        }
        logger.info("DbHealthScheduler: processed {} instances for account {}", snapshots.size(), accountId);
    }

    private AgentInstanceMetricsDto resolveAgentMetrics(Long accountId, String instanceId) {
        try {
            for (DbAgentDataCache cache : dbAgentDataCacheRepository
                    .findByDataTypeStartingWithAndAgentConfig_CloudAccount_Id("db-instance-metrics:", accountId)) {
                if (cache.getDataJson() == null) continue;
                for (AgentInstanceMetricsDto am : objectMapper.readValue(
                        cache.getDataJson(), new TypeReference<List<AgentInstanceMetricsDto>>() {})) {
                    if (am.getDbHost() != null && instanceId != null
                            && (instanceId.contains(am.getDbHost().split(":")[0]) || am.getDbHost().contains(instanceId)))
                        return am;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
