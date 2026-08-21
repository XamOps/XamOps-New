package com.xammer.cloud.service.war;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.war.WarReport;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.war.aws.AwsWarAiChecks;
import com.xammer.cloud.service.war.aws.AwsWarComputeChecks;
import com.xammer.cloud.service.war.aws.AwsWarDataChecks;
import com.xammer.cloud.service.war.aws.AwsWarIdentityChecks;
import com.xammer.cloud.service.war.aws.AwsWarLoggingChecks;
import com.xammer.cloud.service.war.aws.AwsWarMiningChecks;
import com.xammer.cloud.service.war.aws.AwsWarNetworkChecks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Builds the AWS WAR (Workload Attack-surface Review) report for one account.
 *
 * <p>
 * Caching follows the same three-tier ladder as {@code SecurityService} — Redis, then
 * a Postgres snapshot, then live AWS — because a WAR scan makes hundreds of API calls
 * across every active region and cannot run on every page load. The Postgres snapshot
 * also gives the report a history: the daily key doubles as an audit trail of when a
 * given exposure first appeared.
 */
@Service
public class AwsWarService {

    private static final Logger log = LoggerFactory.getLogger(AwsWarService.class);

    /**
     * Cache schema version. <strong>Bump this whenever {@link WarReport} gains, loses or
     * renames a field.</strong>
     *
     * <p>
     * Reports are cached for 12 hours in Redis and indefinitely in Postgres. Without a
     * version in the key, adding a field means the UI reads old payloads where that
     * field is absent — which is how {@code blockedActions} arrived as null and crashed
     * the page on {@code null.length}. Versioning the key makes a schema change a cache
     * miss instead of a runtime error.
     */
    private static final String CACHE_VERSION = "v2";

    private static final String REDIS_PREFIX = "war:aws:" + CACHE_VERSION + ":";
    private static final String DB_PREFIX = "WAR_AWS::" + CACHE_VERSION + "::";
    private static final int REDIS_TTL_MINUTES = 720; // 12h — a scan is expensive, staleness is cheap



    private final AwsWarIdentityChecks identityChecks;
    private final AwsWarNetworkChecks networkChecks;
    private final AwsWarDataChecks dataChecks;
    private final AwsWarLoggingChecks loggingChecks;
    private final AwsWarComputeChecks computeChecks;
    private final AwsWarAiChecks aiChecks;
    private final AwsWarMiningChecks miningChecks;

    private final WarReportAssembler assembler;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    /**
     * Optional so the WAR page keeps working with the live-findings feature disabled, in which case
     * the staleness banner simply never appears.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.xammer.cloud.service.security.SecurityFindingProjectionService liveFindings;

    public AwsWarService(AwsWarIdentityChecks identityChecks,
            AwsWarNetworkChecks networkChecks,
            AwsWarDataChecks dataChecks,
            AwsWarLoggingChecks loggingChecks,
            AwsWarComputeChecks computeChecks,
            AwsWarAiChecks aiChecks,
            AwsWarMiningChecks miningChecks,
            WarReportAssembler assembler,
            RedisCacheService redisCache,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            @Qualifier("backgroundTaskExecutor") Executor executor) {
        this.identityChecks = identityChecks;
        this.networkChecks = networkChecks;
        this.dataChecks = dataChecks;
        this.loggingChecks = loggingChecks;
        this.computeChecks = computeChecks;
        this.aiChecks = aiChecks;
        this.miningChecks = miningChecks;
        this.assembler = assembler;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public CompletableFuture<WarReport> getReport(CloudAccount account, List<String> regions,
            boolean forceRefresh) {

        String accountId = account.getAwsAccountId();
        String redisKey = REDIS_PREFIX + accountId;
        String dbKeyLatest = DB_PREFIX + accountId + "::LATEST";

        if (!forceRefresh) {
            Optional<WarReport> cached = redisCache.get(redisKey, new TypeReference<WarReport>() {
            });
            if (cached.isPresent()) {
                log.info("[WAR] Redis hit for account {}", accountId);
                return CompletableFuture.completedFuture(withStaleness(account, cached.get()));
            }

            Optional<CachedData> snapshot = cachedDataRepository.findById(dbKeyLatest);
            if (snapshot.isPresent()) {
                try {
                    WarReport report = objectMapper.readValue(
                            snapshot.get().getJsonData(), WarReport.class);
                    log.info("[WAR] DB snapshot hit for account {} — re-warming Redis", accountId);
                    // Re-warmed before the staleness stamp so the cached copy stays free of a
                    // value that is only valid at the instant it is read.
                    redisCache.put(redisKey, report, REDIS_TTL_MINUTES);
                    return CompletableFuture.completedFuture(withStaleness(account, report));
                } catch (Exception e) {
                    log.warn("[WAR] Could not parse DB snapshot for {}: {}", accountId, e.getMessage());
                }
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            long startMs = System.currentTimeMillis();
            log.info("[WAR] Starting live scan for account {} across {} region(s)",
                    accountId, regions.size());

            WarScanContext ctx = new WarScanContext(account, regions);

            // Each group is independently guarded, so a denied service degrades that
            // group's controls to UNKNOWN instead of aborting the scan.
            runGroup("identity", () -> identityChecks.run(ctx));
            runGroup("network", () -> networkChecks.run(ctx));
            runGroup("data", () -> dataChecks.run(ctx));
            runGroup("logging", () -> loggingChecks.run(ctx));
            runGroup("compute", () -> computeChecks.run(ctx));
            runGroup("ai", () -> aiChecks.run(ctx));
            runGroup("mining", () -> miningChecks.run(ctx));

            WarReport report = assemble(account, regions, ctx);
            persist(accountId, redisKey, report);

            log.info("[WAR] Scan complete for {} — score {} ({}), {} failing controls, {}ms",
                    accountId, report.getWarScore(), report.getRiskLevel(),
                    report.getStatusCounts().getOrDefault("FAIL", 0),
                    System.currentTimeMillis() - startMs);
            // A freshly computed report has nothing newer than itself by definition, but the stamp
            // is applied anyway: a finding can land during the scan, which takes minutes.
            return withStaleness(account, report);
        }, executor);
    }

    /**
     * Stamps how many event-driven findings arrived after this report was generated.
     *
     * <p>
     * Applied on every return path, including the freshly-scanned one, and never persisted. Never
     * throws: a WAR report that fails to load because a supplementary count could not be computed
     * would be a worse outcome than a missing banner.
     */
    private WarReport withStaleness(CloudAccount account, WarReport report) {
        if (report == null || liveFindings == null || account.getId() == null) {
            return report;
        }
        try {
            Instant generatedAt = parseGeneratedAt(report.getGeneratedAt());
            if (generatedAt != null) {
                report.setFindingsSinceScan(
                        (int) liveFindings.countSince(account.getId(), generatedAt));
            }
        } catch (Exception e) {
            log.debug("[WAR] Could not compute findings-since-scan for {}: {}",
                    account.getAwsAccountId(), e.getMessage());
        }
        return report;
    }

    /**
     * Parses the report's timestamp.
     *
     * <p>
     * {@code generatedAt} is a formatted string rather than an instant, so both the ISO form and a
     * local date-time are accepted. Returning null on an unparseable value leaves the count at zero
     * and hides the banner, which is the right failure: a banner claiming "0 new findings" is
     * honest, one claiming a number derived from a misread timestamp is not.
     */
    private static Instant parseGeneratedAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            // Not ISO-instant; try a local date-time in the JVM zone.
        }
        try {
            return java.time.LocalDateTime.parse(value)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void runGroup(String name, Runnable group) {
        try {
            group.run();
        } catch (Exception e) {
            log.error("[WAR] check group '{}' aborted unexpectedly", name, e);
        }
    }

    // ------------------------------------------------------------------
    // Report assembly
    // ------------------------------------------------------------------

    private WarReport assemble(CloudAccount account, List<String> regions, WarScanContext ctx) {
        return assembler.assemble(
                "AWS",
                account.getAwsAccountId(),
                account.getAccountName(),
                regions,
                WarControlCatalog.AWS,
                WarControlCatalog.AWS_CATEGORY_ORDER,
                ctx);
    }


    private void persist(String accountId, String redisKey, WarReport report) {
        redisCache.put(redisKey, report, REDIS_TTL_MINUTES);
        try {
            String json = objectMapper.writeValueAsString(report);
            LocalDateTime now = LocalDateTime.now();
            // LATEST for fast reload, dated key as the audit trail of when an exposure appeared.
            saveToDb(DB_PREFIX + accountId + "::LATEST", json, now);
            saveToDb(DB_PREFIX + accountId + "::" + LocalDate.now(), json, now);
        } catch (Exception e) {
            log.error("[WAR] Failed to archive report for account {}", accountId, e);
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
