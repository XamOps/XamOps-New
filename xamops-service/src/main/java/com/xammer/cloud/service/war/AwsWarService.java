package com.xammer.cloud.service.war;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.war.WarAttackPath;
import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.dto.war.WarControl;
import com.xammer.cloud.dto.war.WarFinding;
import com.xammer.cloud.dto.war.WarReport;
import com.xammer.cloud.dto.war.WarStatus;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * Score weight per advisory severity. Failing one Critical control matters more
     * than failing four Low ones, so a flat pass-rate percentage would mislead.
     */
    private static final Map<String, Integer> SEVERITY_WEIGHT = Map.of(
            "critical", 10,
            "high", 6,
            "medium", 3,
            "low", 1);

    private final AwsWarIdentityChecks identityChecks;
    private final AwsWarNetworkChecks networkChecks;
    private final AwsWarDataChecks dataChecks;
    private final AwsWarLoggingChecks loggingChecks;
    private final AwsWarComputeChecks computeChecks;
    private final AwsWarAiChecks aiChecks;
    private final AwsWarMiningChecks miningChecks;

    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public AwsWarService(AwsWarIdentityChecks identityChecks,
            AwsWarNetworkChecks networkChecks,
            AwsWarDataChecks dataChecks,
            AwsWarLoggingChecks loggingChecks,
            AwsWarComputeChecks computeChecks,
            AwsWarAiChecks aiChecks,
            AwsWarMiningChecks miningChecks,
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
                return CompletableFuture.completedFuture(cached.get());
            }

            Optional<CachedData> snapshot = cachedDataRepository.findById(dbKeyLatest);
            if (snapshot.isPresent()) {
                try {
                    WarReport report = objectMapper.readValue(
                            snapshot.get().getJsonData(), WarReport.class);
                    log.info("[WAR] DB snapshot hit for account {} — re-warming Redis", accountId);
                    redisCache.put(redisKey, report, REDIS_TTL_MINUTES);
                    return CompletableFuture.completedFuture(report);
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
            return report;
        }, executor);
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
        List<WarControl> catalog = WarControlCatalog.AWS;
        List<WarFinding> findings = new ArrayList<>(catalog.size());

        Map<String, String> blocked = ctx.blockedReasons();
        for (WarControl control : catalog) {
            WarCheckResult result = ctx.results().get(control.id());
            findings.add(toFinding(control, result, blocked.get(control.id())));
        }

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        for (WarStatus status : WarStatus.values()) {
            statusCounts.put(status.name(), 0);
        }
        Map<String, Integer> severityCounts = new LinkedHashMap<>(Map.of(
                "Critical", 0, "High", 0, "Medium", 0, "Low", 0));

        for (WarFinding f : findings) {
            statusCounts.merge(f.getStatus().name(), 1, (a, b) -> a + b);
            if (isOpen(f.getStatus())) {
                severityCounts.merge(normaliseSeverity(f.getSeverity()), 1, (a, b) -> a + b);
            }
        }

        int verified = statusCounts.getOrDefault("PASS", 0)
                + statusCounts.getOrDefault("FAIL", 0)
                + statusCounts.getOrDefault("WARN", 0);

        // Controls that could have produced a verdict: automated, and applicable here.
        int scoreable = verified + statusCounts.getOrDefault("UNKNOWN", 0);

        // A score drawn from a small slice of the baseline is not a security rating.
        // Two verified controls out of sixty-four is a scan failure wearing a number.
        boolean assessable = verified > 0 && verified * 2 >= scoreable;
        String blockedReason = ctx.dominantFailure().orElse(null);

        int score = score(findings);

        // Confirmed exposures only. An unverified control has no fix beyond restoring
        // the scan's access, so listing one under "fix these first" sends the reader to
        // rewrite a policy when the actual problem is upstream.
        List<WarFinding> topRisks = findings.stream()
                .filter(f -> f.getStatus() == WarStatus.FAIL || f.getStatus() == WarStatus.WARN)
                .sorted(Comparator.<WarFinding>comparingInt(f -> f.getRiskWeight()).reversed()
                        .thenComparingInt(f -> f.getOrdinal()))
                .limit(10)
                .toList();

        return WarReport.builder()
                .accountId(account.getAwsAccountId())
                .accountName(account.getAccountName())
                .provider("AWS")
                .generatedAt(LocalDateTime.now(REPORT_ZONE).atZone(REPORT_ZONE).format(TIMESTAMP))
                .scannedRegions(regions)
                .warScore(assessable ? score : 0)
                .riskLevel(assessable
                        ? riskLevel(score, severityCounts.getOrDefault("Critical", 0))
                        : "UNKNOWN")
                .totalControls(catalog.size())
                .automatedControls((int) catalog.stream().filter(c -> c.automated()).count())
                .verifiedControls(verified)
                .assessable(assessable)
                .blockedReason(blockedReason)
                .statusCounts(statusCounts)
                .severityCounts(severityCounts)
                .attackPaths(attackPathSummaries(findings))
                .categories(categorySummaries(findings))
                .findings(findings)
                .topRisks(topRisks)
                .deniedActions(ctx.deniedActions())
                .blockedActions(ctx.blockedActions())
                .scanWarnings(ctx.warnings())
                .build();
    }

    /**
     * @param blockedReason why every region failed for this control, when none produced
     *                     a verdict. Preferred over a generic "not evaluated" message.
     */
    private WarFinding toFinding(WarControl control, WarCheckResult result, String blockedReason) {
        WarStatus status;
        String summary;
        List<String> evidence;
        int affected;
        int scanned;
        String region;
        String denied;

        if (result != null) {
            status = result.getStatus();
            summary = result.getSummary();
            evidence = result.getEvidence();
            affected = result.getAffectedCount();
            scanned = result.getScannedCount();
            region = result.getRegion();
            denied = result.getDeniedAction();
        } else if (control.automated()) {
            // An automated control with no result means no region produced a verdict.
            // Where the scan knows why, say why — a bare "not evaluated" makes the
            // customer go hunting for a cause we already have.
            status = WarStatus.UNKNOWN;
            summary = blockedReason != null
                    ? "Could not verify — " + blockedReason
                    : "This control was not evaluated in the last scan";
            evidence = List.of();
            affected = 0;
            scanned = 0;
            region = null;
            denied = null;
        } else {
            status = WarStatus.MANUAL;
            summary = "Needs human review — no API can prove this from a member account";
            evidence = List.of();
            affected = 0;
            scanned = 0;
            region = null;
            denied = null;
        }

        return WarFinding.builder()
                .controlId(control.id())
                .ordinal(control.ordinal())
                .category(control.category())
                .subCategory(control.subCategory())
                .title(control.title())
                .description(control.description())
                .severity(control.severity())
                .tools(control.tools())
                .reference(control.reference())
                .controlType(control.controlType())
                .businessImpact(control.businessImpact())
                .impactLevel(control.impactLevel())
                .attackPaths(control.attackPaths().stream().map(p -> p.name()).toList())
                .attackPathLabels(control.attackPaths().stream()
                        .map(p -> p.getLabel()).toList())
                .status(status)
                .automated(control.automated())
                .summary(summary)
                .evidence(evidence)
                .affectedCount(affected)
                .scannedCount(scanned)
                .region(region)
                .deniedAction(denied)
                .riskWeight(riskWeight(control, status, affected))
                .build();
    }

    /**
     * A finding's weight is its severity weight, doubled for a hard FAIL and nudged up
     * by how many resources are actually affected. The resource bump is capped so a
     * hundred unversioned buckets cannot outrank an open root account.
     */
    private int riskWeight(WarControl control, WarStatus status, int affectedCount) {
        int base = SEVERITY_WEIGHT.getOrDefault(
                normaliseSeverity(control.severity()).toLowerCase(), 1);
        int multiplier = switch (status) {
            case FAIL -> 2;
            case WARN -> 1;
            case UNKNOWN -> 1;
            default -> 0;
        };
        int spread = Math.min(affectedCount, 5);
        return base * multiplier + spread;
    }

    /**
     * Score is the weighted pass rate across controls that produced a verdict.
     *
     * <p>
     * Only PASS, FAIL and WARN are scored. MANUAL and NOT_APPLICABLE are excluded
     * because scoring them would either punish an account for a control that does not
     * apply to it or reward it for one nobody checked.
     *
     * <p>
     * UNKNOWN is excluded too, which is a deliberate reversal. Counting unverifiable
     * controls as failures made a blocked scan indistinguishable from a genuinely
     * insecure account: 61 controls blocked by an expired token produced "score 0,
     * critical risk", which reads as an indictment of the customer's security rather
     * than a report of our own blindness. Coverage is reported separately instead — see
     * {@code verifiedControls} and {@code assessable}.
     */
    private int score(List<WarFinding> findings) {
        int earned = 0;
        int possible = 0;

        for (WarFinding f : findings) {
            if (!isScored(f.getStatus())) {
                continue;
            }
            int weight = SEVERITY_WEIGHT.getOrDefault(
                    normaliseSeverity(f.getSeverity()).toLowerCase(), 1);
            possible += weight;
            switch (f.getStatus()) {
                case PASS -> earned += weight;
                // A partially-mitigated control earns half credit.
                case WARN -> earned += weight / 2;
                default -> {
                }
            }
        }

        return possible == 0 ? 0 : (int) Math.round(100.0 * earned / possible);
    }

    /** PASS, FAIL and WARN are verdicts. Everything else is an absence of one. */
    private static boolean isScored(WarStatus status) {
        return status == WarStatus.PASS || status == WarStatus.FAIL || status == WarStatus.WARN;
    }

    private String riskLevel(int score, int openCriticals) {
        // Open criticals dominate. An account can score 85 and still have its root user
        // unprotected, and calling that "low risk" would be wrong.
        if (openCriticals >= 3 || score < 50) {
            return "CRITICAL";
        }
        if (openCriticals >= 1 || score < 70) {
            return "HIGH";
        }
        return score < 85 ? "MEDIUM" : "LOW";
    }

    private List<WarReport.AttackPathSummary> attackPathSummaries(List<WarFinding> findings) {
        Map<WarAttackPath, List<WarFinding>> byPath = new EnumMap<>(WarAttackPath.class);
        for (WarAttackPath path : WarAttackPath.values()) {
            byPath.put(path, new ArrayList<>());
        }
        for (WarFinding f : findings) {
            for (String name : f.getAttackPaths()) {
                byPath.get(WarAttackPath.valueOf(name)).add(f);
            }
        }

        List<WarReport.AttackPathSummary> summaries = new ArrayList<>();
        for (var entry : byPath.entrySet()) {
            List<WarFinding> group = entry.getValue();
            if (group.isEmpty()) {
                continue;
            }
            int failed = count(group, WarStatus.FAIL);
            int warned = count(group, WarStatus.WARN);
            int unknown = count(group, WarStatus.UNKNOWN);
            int affected = group.stream().mapToInt(f -> f.getAffectedCount()).sum();

            String worst = group.stream()
                    .filter(f -> isOpen(f.getStatus()))
                    .map(f -> normaliseSeverity(f.getSeverity()))
                    .min(Comparator.comparingInt(s -> switch (s) {
                        case "Critical" -> 0;
                        case "High" -> 1;
                        case "Medium" -> 2;
                        default -> 3;
                    }))
                    .orElse("None");

            String state;
            if (failed > 0) {
                state = "OPEN";
            } else if (warned > 0) {
                state = "PARTIAL";
            } else if (unknown > 0) {
                state = "UNVERIFIED";
            } else {
                state = "CONTAINED";
            }

            summaries.add(WarReport.AttackPathSummary.builder()
                    .path(entry.getKey().name())
                    .label(entry.getKey().getLabel())
                    .description(entry.getKey().getDescription())
                    .failed(failed)
                    .warned(warned)
                    .unverified(unknown)
                    .total(group.size())
                    .affectedResources(affected)
                    .state(state)
                    .worstSeverity(worst)
                    .topControlIds(group.stream()
                            .filter(f -> isOpen(f.getStatus()))
                            .sorted(Comparator.<WarFinding>comparingInt(f -> f.getRiskWeight())
                                    .reversed())
                            .limit(5)
                            .map(f -> f.getControlId())
                            .toList())
                    .build());
        }

        // Most exposed path first — this ordering is what the page leads with. Confirmed
        // failures outrank weakened ones, which outrank merely unverified ones.
        summaries.sort(Comparator.<WarReport.AttackPathSummary>comparingInt(s -> s.getFailed())
                .thenComparingInt(s -> s.getWarned())
                .thenComparingInt(s -> s.getUnverified())
                .reversed());
        return summaries;
    }

    private List<WarReport.CategorySummary> categorySummaries(List<WarFinding> findings) {
        Map<String, List<WarFinding>> byCategory = new LinkedHashMap<>();
        for (String category : WarControlCatalog.AWS_CATEGORY_ORDER) {
            byCategory.put(category, new ArrayList<>());
        }
        findings.forEach(f -> byCategory
                .computeIfAbsent(f.getCategory(), k -> new ArrayList<>()).add(f));

        List<WarReport.CategorySummary> summaries = new ArrayList<>();
        for (var entry : byCategory.entrySet()) {
            List<WarFinding> group = entry.getValue();
            if (group.isEmpty()) {
                continue;
            }
            summaries.add(WarReport.CategorySummary.builder()
                    .category(entry.getKey())
                    .total(group.size())
                    .passed(count(group, WarStatus.PASS))
                    .failed(count(group, WarStatus.FAIL))
                    .warned(count(group, WarStatus.WARN))
                    .manual(count(group, WarStatus.MANUAL))
                    .unknown(count(group, WarStatus.UNKNOWN))
                    .notApplicable(count(group, WarStatus.NOT_APPLICABLE))
                    .score(score(group))
                    .build());
        }
        return summaries;
    }

    private static int count(List<WarFinding> findings, WarStatus status) {
        return (int) findings.stream().filter(f -> f.getStatus() == status).count();
    }

    /** Open means "an attacker could use this today", which includes unverifiable. */
    private static boolean isOpen(WarStatus status) {
        return status == WarStatus.FAIL || status == WarStatus.WARN || status == WarStatus.UNKNOWN;
    }

    private static String normaliseSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return "Low";
        }
        String trimmed = severity.trim();
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1).toLowerCase();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

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
