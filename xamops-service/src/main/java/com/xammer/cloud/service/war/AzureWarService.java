package com.xammer.cloud.service.war;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.war.WarReport;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.war.azure.AzureWarGovernanceChecks;
import com.xammer.cloud.service.war.azure.AzureWarResourceChecks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Builds the Azure WAR (Workload Attack-surface Review) report for one subscription.
 *
 * <p>
 * Mirrors {@code AwsWarService} and {@code GcpWarService}: same three-tier cache ladder,
 * same assembler, same scoring. Azure resources are enumerated subscription-wide rather
 * than per region, so like GCP there is no regional fan-out — the "scanned regions" field
 * carries the subscription id.
 */
@Service
public class AzureWarService {

    private static final Logger log = LoggerFactory.getLogger(AzureWarService.class);

    /**
     * Cache schema version. <strong>Bump this whenever {@link WarReport} gains, loses or
     * renames a field</strong> — see the note on AwsWarService for why.
     */
    private static final String CACHE_VERSION = "v2";

    private static final String REDIS_PREFIX = "war:azure:" + CACHE_VERSION + ":";
    private static final String DB_PREFIX = "WAR_AZURE::" + CACHE_VERSION + "::";
    private static final int REDIS_TTL_MINUTES = 720; // 12h — a scan is expensive, staleness is cheap

    private final AzureWarGovernanceChecks governanceChecks;
    private final AzureWarResourceChecks resourceChecks;

    private final WarReportAssembler assembler;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public AzureWarService(AzureWarGovernanceChecks governanceChecks,
            AzureWarResourceChecks resourceChecks,
            WarReportAssembler assembler,
            RedisCacheService redisCache,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            @Qualifier("backgroundTaskExecutor") Executor executor) {
        this.governanceChecks = governanceChecks;
        this.resourceChecks = resourceChecks;
        this.assembler = assembler;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public CompletableFuture<WarReport> getReport(CloudAccount account, boolean forceRefresh) {
        String subscriptionId = account.getAzureSubscriptionId();
        String redisKey = REDIS_PREFIX + subscriptionId;
        String dbKeyLatest = DB_PREFIX + subscriptionId + "::LATEST";

        if (!forceRefresh) {
            Optional<WarReport> cached = redisCache.get(redisKey, new TypeReference<WarReport>() {
            });
            if (cached.isPresent()) {
                log.info("[WAR/Azure] Redis hit for subscription {}", subscriptionId);
                return CompletableFuture.completedFuture(cached.get());
            }

            Optional<CachedData> snapshot = cachedDataRepository.findById(dbKeyLatest);
            if (snapshot.isPresent()) {
                try {
                    WarReport report = objectMapper.readValue(
                            snapshot.get().getJsonData(), WarReport.class);
                    log.info("[WAR/Azure] DB snapshot hit for {} — re-warming Redis", subscriptionId);
                    redisCache.put(redisKey, report, REDIS_TTL_MINUTES);
                    return CompletableFuture.completedFuture(report);
                } catch (Exception e) {
                    log.warn("[WAR/Azure] Could not parse DB snapshot for {}: {}",
                            subscriptionId, e.getMessage());
                }
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            long startMs = System.currentTimeMillis();
            log.info("[WAR/Azure] Starting live scan for subscription {}", subscriptionId);

            WarScanContext ctx = new WarScanContext(account, List.of(subscriptionId));

            runGroup("governance", () -> governanceChecks.run(ctx));
            runGroup("resources", () -> resourceChecks.run(ctx));

            WarReport report = assembler.assemble(
                    "Azure",
                    subscriptionId,
                    account.getAccountName(),
                    List.of(subscriptionId),
                    AzureWarControlCatalog.AZURE,
                    AzureWarControlCatalog.AZURE_CATEGORY_ORDER,
                    ctx);

            persist(subscriptionId, redisKey, report);

            log.info("[WAR/Azure] Scan complete for {} — score {} ({}), {} failing controls, {}ms",
                    subscriptionId, report.getWarScore(), report.getRiskLevel(),
                    report.getStatusCounts().getOrDefault("FAIL", 0),
                    System.currentTimeMillis() - startMs);
            return report;
        }, executor);
    }

    private void runGroup(String name, Runnable group) {
        try {
            group.run();
        } catch (Exception e) {
            log.error("[WAR/Azure] check group '{}' aborted unexpectedly", name, e);
        }
    }

    private void persist(String subscriptionId, String redisKey, WarReport report) {
        redisCache.put(redisKey, report, REDIS_TTL_MINUTES);
        try {
            String json = objectMapper.writeValueAsString(report);
            LocalDateTime now = LocalDateTime.now();
            // LATEST for fast reload, dated key as the audit trail of when an exposure appeared.
            saveToDb(DB_PREFIX + subscriptionId + "::LATEST", json, now);
            saveToDb(DB_PREFIX + subscriptionId + "::" + LocalDate.now(), json, now);
        } catch (Exception e) {
            log.error("[WAR/Azure] Failed to archive report for subscription {}",
                    subscriptionId, e);
        }
    }

    private void saveToDb(String key, String json, LocalDateTime now) {
        CachedData data = cachedDataRepository.findById(key).orElseGet(() -> new CachedData());
        data.setCacheKey(key);
        data.setJsonData(json);
        data.setLastUpdated(now);
        cachedDataRepository.save(data);
    }
}
