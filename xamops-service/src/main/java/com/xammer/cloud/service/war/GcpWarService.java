package com.xammer.cloud.service.war;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.war.WarReport;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.war.gcp.GcpWarComputeChecks;
import com.xammer.cloud.service.war.gcp.GcpWarDetectionChecks;
import com.xammer.cloud.service.war.gcp.GcpWarIdentityChecks;
import com.xammer.cloud.service.war.gcp.GcpWarNetworkChecks;
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
 * Builds the GCP WAR (Workload Attack-surface Review) report for one project.
 *
 * <p>
 * Mirrors {@code AwsWarService}: same three-tier cache ladder, same assembler, same
 * scoring. GCP resources are mostly global or aggregated across zones in a single call,
 * so unlike AWS there is no per-region fan-out — the "scanned regions" field carries the
 * project id instead, and the checks use {@code aggregatedList} where a resource is
 * zonal.
 */
@Service
public class GcpWarService {

    private static final Logger log = LoggerFactory.getLogger(GcpWarService.class);

    /**
     * Cache schema version. <strong>Bump this whenever {@link WarReport} gains, loses or
     * renames a field</strong> — see the note on AwsWarService for why.
     */
    private static final String CACHE_VERSION = "v2";

    private static final String REDIS_PREFIX = "war:gcp:" + CACHE_VERSION + ":";
    private static final String DB_PREFIX = "WAR_GCP::" + CACHE_VERSION + "::";
    private static final int REDIS_TTL_MINUTES = 720; // 12h — a scan is expensive, staleness is cheap

    private final GcpWarIdentityChecks identityChecks;
    private final GcpWarNetworkChecks networkChecks;
    private final GcpWarComputeChecks computeChecks;
    private final GcpWarDetectionChecks detectionChecks;

    private final WarReportAssembler assembler;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public GcpWarService(GcpWarIdentityChecks identityChecks,
            GcpWarNetworkChecks networkChecks,
            GcpWarComputeChecks computeChecks,
            GcpWarDetectionChecks detectionChecks,
            WarReportAssembler assembler,
            RedisCacheService redisCache,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            @Qualifier("backgroundTaskExecutor") Executor executor) {
        this.identityChecks = identityChecks;
        this.networkChecks = networkChecks;
        this.computeChecks = computeChecks;
        this.detectionChecks = detectionChecks;
        this.assembler = assembler;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public CompletableFuture<WarReport> getReport(CloudAccount account, boolean forceRefresh) {
        String projectId = account.getGcpProjectId();
        String redisKey = REDIS_PREFIX + projectId;
        String dbKeyLatest = DB_PREFIX + projectId + "::LATEST";

        if (!forceRefresh) {
            Optional<WarReport> cached = redisCache.get(redisKey, new TypeReference<WarReport>() {
            });
            if (cached.isPresent()) {
                log.info("[WAR/GCP] Redis hit for project {}", projectId);
                return CompletableFuture.completedFuture(cached.get());
            }

            Optional<CachedData> snapshot = cachedDataRepository.findById(dbKeyLatest);
            if (snapshot.isPresent()) {
                try {
                    WarReport report = objectMapper.readValue(
                            snapshot.get().getJsonData(), WarReport.class);
                    log.info("[WAR/GCP] DB snapshot hit for project {} — re-warming Redis", projectId);
                    redisCache.put(redisKey, report, REDIS_TTL_MINUTES);
                    return CompletableFuture.completedFuture(report);
                } catch (Exception e) {
                    log.warn("[WAR/GCP] Could not parse DB snapshot for {}: {}",
                            projectId, e.getMessage());
                }
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            long startMs = System.currentTimeMillis();
            log.info("[WAR/GCP] Starting live scan for project {}", projectId);

            // GCP resources are global or aggregated, so the context carries the project
            // as its single "region" — the guard helpers still key warnings off it.
            WarScanContext ctx = new WarScanContext(account, List.of(projectId));

            runGroup("identity", () -> identityChecks.run(ctx));
            runGroup("network", () -> networkChecks.run(ctx));
            runGroup("compute", () -> computeChecks.run(ctx));
            runGroup("detection", () -> detectionChecks.run(ctx));

            WarReport report = assembler.assemble(
                    "GCP",
                    projectId,
                    account.getAccountName(),
                    List.of(projectId),
                    GcpWarControlCatalog.GCP,
                    GcpWarControlCatalog.GCP_CATEGORY_ORDER,
                    ctx);

            persist(projectId, redisKey, report);

            log.info("[WAR/GCP] Scan complete for {} — score {} ({}), {} failing controls, {}ms",
                    projectId, report.getWarScore(), report.getRiskLevel(),
                    report.getStatusCounts().getOrDefault("FAIL", 0),
                    System.currentTimeMillis() - startMs);
            return report;
        }, executor);
    }

    private void runGroup(String name, Runnable group) {
        try {
            group.run();
        } catch (Exception e) {
            log.error("[WAR/GCP] check group '{}' aborted unexpectedly", name, e);
        }
    }

    private void persist(String projectId, String redisKey, WarReport report) {
        redisCache.put(redisKey, report, REDIS_TTL_MINUTES);
        try {
            String json = objectMapper.writeValueAsString(report);
            LocalDateTime now = LocalDateTime.now();
            // LATEST for fast reload, dated key as the audit trail of when an exposure appeared.
            saveToDb(DB_PREFIX + projectId + "::LATEST", json, now);
            saveToDb(DB_PREFIX + projectId + "::" + LocalDate.now(), json, now);
        } catch (Exception e) {
            log.error("[WAR/GCP] Failed to archive report for project {}", projectId, e);
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
