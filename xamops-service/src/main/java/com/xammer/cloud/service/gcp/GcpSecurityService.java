package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.cloud.securitycenter.v2.Finding;
import com.google.cloud.securitycenter.v2.GroupFindingsRequest;
import com.google.cloud.securitycenter.v2.GroupResult;
import com.google.cloud.securitycenter.v2.ListFindingsRequest;
import com.google.cloud.securitycenter.v2.ListFindingsResponse;
import com.google.cloud.securitycenter.v2.SecurityCenterClient;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.ProwlerFinding;
import com.xammer.cloud.dto.gcp.GcpContainerVulnerabilityDto;
import com.xammer.cloud.dto.gcp.GcpIamPolicyDriftDto;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GcpSecurityService {

    private final GcpClientProvider gcpClientProvider;
    private final RedisCacheService redisCacheService;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final ProwlerService prowlerService;
    private final JdbcTemplate jdbcTemplate;

    private final Executor executor = Executors.newCachedThreadPool();

    private static final String REDIS_SECURITY_PREFIX   = "gcp:security-findings:";
    private static final String REDIS_IAM_DRIFT_PREFIX  = "gcp:iam-policy-drift:";
    private static final String REDIS_CONTAINER_PREFIX  = "gcp:container-scanning:";
    private static final String REDIS_DIGEST_PREFIX     = "gcp:security-digest:";
    private static final int    REDIS_TTL_MINUTES       = 1440; // Gap D: 24h aligned with staleness threshold

    /**
     * Most findings kept per project in the returned list, Redis and the DB snapshot. The most
     * severe are kept first. Exact totals survive in {@link GcpSecurityDigest}.
     */
    @Value("${xamops.gcp.security.max-findings:5000}")
    private int maxFindings = 5000;

    /** A stored snapshot larger than this many characters is never loaded or written. */
    @Value("${xamops.gcp.security.max-snapshot-bytes:20000000}")
    private long maxSnapshotBytes = 20_000_000L;

    /** SCC ListFindings page size (SCC allows 1..1000). */
    @Value("${xamops.gcp.security.page-size:500}")
    private int sccPageSize = 500;

    /** Most SCC pages read per project per scan, across all severity tiers. */
    @Value("${xamops.gcp.security.max-pages:20}")
    private int sccMaxPages = 20;

    /** Back-off after the first failure for a project; doubles per consecutive failure. */
    @Value("${xamops.gcp.security.backoff.initial-seconds:30}")
    private long backoffInitialSeconds = 30;

    /** Ceiling for the back-off delay. */
    @Value("${xamops.gcp.security.backoff.max-seconds:1800}")
    private long backoffMaxSeconds = 1800;

    /** After this many consecutive failures the delay stays at the ceiling. */
    @Value("${xamops.gcp.security.backoff.max-consecutive-failures:8}")
    private int backoffMaxConsecutiveFailures = 8;

    /** A forced refresh may bypass back-off only if the last attempt was at least this long ago. */
    private static final long FORCE_REFRESH_MIN_INTERVAL_SECONDS = 60;

    /** Consecutive-failure state per project. In-memory: a restart resets it, which is acceptable. */
    private final ConcurrentMap<String, FailureState> failures = new ConcurrentHashMap<>();

    private record FailureState(int consecutive, Instant lastAttempt, Instant nextAttempt) {
    }

    /**
     * How old a DB snapshot may be and still be served.
     *
     * <p>
     * Past this the snapshot is ignored rather than returned-and-refreshed-in-the-background. That
     * older behaviour is what let a resolved finding persist: the row carries no expiry, so serving
     * it also re-warmed Redis for another {@link #REDIS_TTL_MINUTES}, and the stale list renewed
     * its own lifetime on every read. Falling through to a live scan costs a slow request; showing
     * a customer findings they have already fixed costs their trust in the whole page.
     */
    private static final int SNAPSHOT_MAX_AGE_HOURS = 24;

    // Shared with the weekly scheduler via ProwlerFrameworks. They previously disagreed — the
    // scheduler asked for pci_3.2.1_gcp / iso27001_2013_gcp / gdpr_gcp / nist_csf_1.1_gcp, none of
    // which GCP offers, and one invalid name makes Prowler reject the whole --compliance argument
    // and scan nothing.
    private static final String[] GCP_PROWLER_FRAMEWORKS =
            com.xammer.cloud.service.security.ProwlerFrameworks.GCP;

    public GcpSecurityService(GcpClientProvider gcpClientProvider,
                              RedisCacheService redisCacheService,
                              CachedDataRepository cachedDataRepository,
                              ObjectMapper objectMapper,
                              ProwlerService prowlerService,
                              JdbcTemplate jdbcTemplate) {
        this.gcpClientProvider = gcpClientProvider;
        this.redisCacheService = redisCacheService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.prowlerService = prowlerService;
        this.jdbcTemplate = jdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // Main security findings — public API
    // -------------------------------------------------------------------------

    public CompletableFuture<List<GcpSecurityFinding>> getSecurityFindings(String gcpProjectId) {
        return getSecurityFindings(gcpProjectId, false);
    }

    /** Gap 5: forceRefresh param. Gap B/C/D/E/F all implemented here. */
    public CompletableFuture<List<GcpSecurityFinding>> getSecurityFindings(String gcpProjectId, boolean forceRefresh) {
        String redisKey   = REDIS_SECURITY_PREFIX + gcpProjectId;
        String dbKeyLatest = "GCP_SECURITY::" + gcpProjectId + "::LATEST";
        String dbKeyDate   = "GCP_SECURITY::" + gcpProjectId + "::" + LocalDate.now();

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Per-project circuit breaker: a project that keeps failing is not retried more
                // often than its back-off window, so one bad project cannot hammer SCC and the DB.
                List<GcpSecurityFinding> backedOff = serveIfBackedOff(gcpProjectId, forceRefresh, redisKey);
                if (backedOff != null) {
                    return backedOff;
                }

                if (!forceRefresh) {
                    // L1: Redis
                    Optional<List<GcpSecurityFinding>> cached = redisCacheService.get(redisKey, new TypeReference<>() {});
                    if (cached.isPresent()) {
                        log.info("✅ Redis hit for GCP security findings project={}", gcpProjectId);
                        return cached.get();
                    }

                    // L2: DB LATEST (Gap B — dedicated LATEST key), but only while it is still
                    // young enough to be worth trusting.
                    //
                    // This tier used to serve unconditionally AND push what it found back into
                    // Redis for a further TTL. That combination is why a customer who had fixed
                    // their findings kept seeing them: the row has no expiry column, so every read
                    // renewed its own cache entry and the stale list never aged out. A snapshot
                    // past the threshold is now skipped entirely so the request falls through to a
                    // live scan — slower, and correct.
                    try {
                        if (isOversizedSnapshot(dbKeyLatest, gcpProjectId)) {
                            // Skipped without loading it: reading a row this size is what ran the
                            // JVM out of memory. The next successful scan replaces it with a capped one.
                        } else {
                            Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKeyLatest);
                            if (dbSnap.isPresent()) {
                                if (isStale(dbSnap.get().getLastUpdated())) {
                                    log.info("[GcpSecurity] DB snapshot for project={} is older than {}h — ignoring it "
                                            + "and scanning live", gcpProjectId, SNAPSHOT_MAX_AGE_HOURS);
                                } else {
                                    List<GcpSecurityFinding> dbResult = objectMapper.readValue(
                                            dbSnap.get().getJsonData(), new TypeReference<>() {});
                                    log.info("✅ DB LATEST hit for GCP security project={} ({} findings) — re-warming Redis",
                                            gcpProjectId, dbResult.size());
                                    redisCacheService.put(redisKey, dbResult, REDIS_TTL_MINUTES);
                                    return dbResult;
                                }
                            }
                        }
                    } catch (Exception e) {
                        rethrowIfOutOfMemory(e, gcpProjectId, "loading the DB LATEST snapshot");
                        log.warn("[GcpSecurity] DB LATEST load failed for project={}: {}", gcpProjectId, e.getMessage());
                    }
                }

                // L3: Live SCC API
                log.info("🔍 Fetching fresh GCP security findings from SCC for project={}", gcpProjectId);
                SccScan scan = fetchFromScc(gcpProjectId);

                // Gap E: merge Prowler cached findings, through the same bounded collector
                GcpSecurityFindingCollector collector = scan.collector();
                long sccCount = collector.total();
                Map<String, Long> sccSeverityTotals = collector.severityTotalsSnapshot();
                int prowlerCount = mapProwlerFindings(gcpProjectId, collector::add);
                log.info("[GcpSecurity] Merging {} SCC + {} Prowler findings for project={}",
                        sccCount, prowlerCount, gcpProjectId);
                List<GcpSecurityFinding> combined = collector.retained();
                GcpSecurityDigest digest = buildDigest(scan, collector, prowlerSeverityTotals(collector, sccSeverityTotals),
                        combined.size());
                if (digest.truncated()) {
                    log.warn("[GcpSecurity] Truncated findings for project={}: kept {} of {} (cap "
                                    + "xamops.gcp.security.max-findings={}); severity totals={}",
                            gcpProjectId, combined.size(), digest.total(), maxFindings, digest.severityTotals());
                    if (digest.approximate()) {
                        log.warn("[GcpSecurity] Totals for project={} are a lower bound: the SCC fetch stopped at its "
                                + "cap and exact counts (GroupFindings) were unavailable", gcpProjectId);
                    }
                }

                // Persist on the strength of whether the scan SUCCEEDED, not whether it returned
                // anything.
                //
                // The previous rule was "never overwrite a good snapshot with an empty one", which
                // sounds protective and is the whole bug: a customer who has just remediated
                // everything is exactly the customer whose correct answer is an empty list, and
                // that answer was the one being thrown away. Because the row has no expiry, the
                // old findings then came back on the next read and stayed indefinitely.
                //
                // The distinction that actually matters is success versus failure, which the
                // scan now reports rather than flattening both into an empty list.
                if (scan.succeeded()) {
                    failures.remove(gcpProjectId);
                    redisCacheService.put(redisKey, combined, REDIS_TTL_MINUTES);
                    redisCacheService.put(REDIS_DIGEST_PREFIX + gcpProjectId, digest, REDIS_TTL_MINUTES);
                    archiveFindings(gcpProjectId, combined, digest, dbKeyLatest, dbKeyDate);
                    if (combined.isEmpty()) {
                        log.info("[GcpSecurity] Scan succeeded with no active findings for project={} — recording "
                                + "the clean result", gcpProjectId);
                    }
                } else {
                    recordFailure(gcpProjectId);
                    // Could not reach SCC. Keep the last known snapshot rather than blanking the
                    // page, and serve it, so an outage does not read as "you are now secure".
                    log.warn("[GcpSecurity] SCC scan FAILED for project={} — keeping the previous snapshot",
                            gcpProjectId);
                    List<GcpSecurityFinding> lastKnown = lastKnownSnapshot(dbKeyLatest, gcpProjectId);
                    if (lastKnown != null) {
                        return lastKnown;
                    }
                }

                return combined;
            } catch (OutOfMemoryError oom) {
                // Not swallowed: log which project, record the failure so it backs off, and let the
                // Error propagate. The JVM is configured to dump the heap and exit on OOM.
                log.error("[GcpSecurity] OutOfMemoryError fetching findings for project={}", gcpProjectId, oom);
                recordFailure(gcpProjectId);
                throw oom;
            } catch (Exception e) {
                log.error("[GcpSecurity] CRITICAL ERROR fetching findings for project={}", gcpProjectId, e);
                recordFailure(gcpProjectId);
                return Collections.emptyList();
            }
        }, executor);
    }

    // -------------------------------------------------------------------------
    // IAM Policy Drift — flags risky/over-privileged project IAM bindings
    //   • allUsers / allAuthenticatedUsers (public access)
    //   • primitive roles (roles/owner, roles/editor) granted to users/groups
    //   • service accounts holding primitive owner/editor roles
    // Source: Resource Manager getIamPolicy (free, read-only).
    // -------------------------------------------------------------------------

    public CompletableFuture<List<GcpIamPolicyDriftDto>> getIamPolicyDrift(String gcpProjectId) {
        return getIamPolicyDrift(gcpProjectId, false);
    }

    public CompletableFuture<List<GcpIamPolicyDriftDto>> getIamPolicyDrift(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = REDIS_IAM_DRIFT_PREFIX + gcpProjectId;
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (forceRefresh) {
                    redisCacheService.evict(cacheKey);
                } else {
                    Optional<List<GcpIamPolicyDriftDto>> cached = redisCacheService.get(
                            cacheKey, new TypeReference<>() {});
                    if (cached.isPresent()) {
                        log.info("✅ Redis hit for GCP IAM drift project={}", gcpProjectId);
                        return cached.get();
                    }
                }

                List<GcpIamPolicyDriftDto> result = analyzeIamPolicy(gcpProjectId);
                log.info("[GcpSecurity] IAM drift analysis found {} risky bindings for project={}",
                        result.size(), gcpProjectId);
                redisCacheService.put(cacheKey, result, REDIS_TTL_MINUTES);
                return result;
            } catch (Exception t) {
                log.error("[GcpSecurity] IAM drift failed for project={}: {}", gcpProjectId, t.getMessage(), t);
                return Collections.emptyList();
            }
        }, executor);
    }

    private List<GcpIamPolicyDriftDto> analyzeIamPolicy(String gcpProjectId) {
        List<GcpIamPolicyDriftDto> result = new ArrayList<>();
        Optional<ProjectsClient> clientOpt = gcpClientProvider.getProjectsClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            log.warn("[GcpSecurity] ProjectsClient unavailable for IAM drift project={}", gcpProjectId);
            return result;
        }

        try (ProjectsClient client = clientOpt.get()) {
            GetIamPolicyRequest request = GetIamPolicyRequest.newBuilder()
                    .setResource("projects/" + gcpProjectId)
                    .build();
            Policy policy = client.getIamPolicy(request);

            for (Binding binding : policy.getBindingsList()) {
                String role = binding.getRole();
                boolean primitive = "roles/owner".equals(role) || "roles/editor".equals(role);

                for (String member : binding.getMembersList()) {
                    boolean isPublic = "allUsers".equals(member) || "allAuthenticatedUsers".equals(member);
                    boolean isServiceAccount = member.startsWith("serviceAccount:");

                    String status = null;
                    if (isPublic) {
                        status = "PUBLIC_ACCESS";
                    } else if (primitive && isServiceAccount) {
                        status = "SA_PRIMITIVE_ROLE";
                    } else if (primitive) {
                        status = "PRIMITIVE_ROLE";
                    }

                    if (status != null) {
                        result.add(new GcpIamPolicyDriftDto(
                                "projects/" + gcpProjectId,
                                List.of(new GcpIamPolicyDriftDto.DriftDetail(member, role, status))));
                    }
                }
            }
        } catch (com.google.api.gax.rpc.PermissionDeniedException e) {
            log.warn("[GcpSecurity] No permission to read IAM policy for project={} " +
                    "(needs resourcemanager.projects.getIamPolicy)", gcpProjectId);
        } catch (Exception e) {
            log.error("[GcpSecurity] Failed to analyze IAM policy for project={}: {}", gcpProjectId, e.getMessage(), e);
        }

        // Most severe first: public access, then SA primitive, then user primitive
        result.sort(Comparator.comparingInt(d -> {
            String s = d.getDriftDetails().get(0).getStatus();
            if ("PUBLIC_ACCESS".equals(s)) return 0;
            if ("SA_PRIMITIVE_ROLE".equals(s)) return 1;
            return 2;
        }));
        return result;
    }

    // -------------------------------------------------------------------------
    // Container Vulnerabilities — Artifact/Container Analysis occurrences
    // Lists VULNERABILITY occurrences for images scanned in this project.
    // Source: containeranalysis.googleapis.com (free; requires the
    // Container Scanning API enabled + containeranalysis.occurrences.list).
    // -------------------------------------------------------------------------

    public CompletableFuture<List<GcpContainerVulnerabilityDto>> getContainerScanningResults(String gcpProjectId) {
        return getContainerScanningResults(gcpProjectId, false);
    }

    public CompletableFuture<List<GcpContainerVulnerabilityDto>> getContainerScanningResults(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = REDIS_CONTAINER_PREFIX + gcpProjectId;
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (forceRefresh) {
                    redisCacheService.evict(cacheKey);
                } else {
                    Optional<List<GcpContainerVulnerabilityDto>> cached = redisCacheService.get(
                            cacheKey, new TypeReference<>() {});
                    if (cached.isPresent()) {
                        log.info("✅ Redis hit for GCP container vulnerabilities project={}", gcpProjectId);
                        return cached.get();
                    }
                }

                List<GcpContainerVulnerabilityDto> result = fetchContainerVulnerabilities(gcpProjectId);
                log.info("[GcpSecurity] Container scanning found {} vulnerabilities for project={}",
                        result.size(), gcpProjectId);
                redisCacheService.put(cacheKey, result, REDIS_TTL_MINUTES);
                return result;
            } catch (Exception t) {
                log.error("[GcpSecurity] Container scan failed for project={}: {}", gcpProjectId, t.getMessage(), t);
                return Collections.emptyList();
            }
        }, executor);
    }

    private List<GcpContainerVulnerabilityDto> fetchContainerVulnerabilities(String gcpProjectId) {
        List<GcpContainerVulnerabilityDto> result = new ArrayList<>();
        Optional<String> tokenOpt = gcpClientProvider.getAccessToken(gcpProjectId);
        if (tokenOpt.isEmpty()) {
            log.warn("[GcpSecurity] No access token for container scanning project={}", gcpProjectId);
            return result;
        }

        try {
            HttpClient httpClient = HttpClient.newHttpClient();
            String pageToken = null;
            int pages = 0;

            do {
                String uri = "https://containeranalysis.googleapis.com/v1/projects/" + gcpProjectId
                        + "/occurrences?filter=" + java.net.URLEncoder.encode("kind=\"VULNERABILITY\"",
                                java.nio.charset.StandardCharsets.UTF_8)
                        + "&pageSize=500"
                        + (pageToken != null ? "&pageToken=" + pageToken : "");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(uri))
                        .header("Authorization", "Bearer " + tokenOpt.get())
                        .header("Content-Type", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status == 403) {
                    log.warn("[GcpSecurity] Container Scanning API not enabled or no permission for project={}", gcpProjectId);
                    return result;
                }
                if (status != 200) {
                    log.warn("[GcpSecurity] Container Analysis returned {} for project={}: {}", status, gcpProjectId,
                            response.body().substring(0, Math.min(200, response.body().length())));
                    return result;
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode occurrences = root.path("occurrences");
                if (occurrences.isArray()) {
                    for (JsonNode occ : occurrences) {
                        parseVulnerabilityOccurrence(occ, result);
                    }
                }
                pageToken = root.path("nextPageToken").asText(null);
                pages++;
            } while (pageToken != null && !pageToken.isBlank() && pages < 10);

        } catch (Exception e) {
            log.error("[GcpSecurity] Failed to fetch container vulnerabilities for project={}: {}",
                    gcpProjectId, e.getMessage(), e);
        }

        // Highest severity first
        result.sort(Comparator.comparingInt(v -> severityRank(v.getSeverity())));
        return result;
    }

    private void parseVulnerabilityOccurrence(JsonNode occ, List<GcpContainerVulnerabilityDto> result) {
        try {
            JsonNode vuln = occ.path("vulnerability");
            if (vuln.isMissingNode()) return;

            String resourceUri = occ.path("resourceUri").asText("");
            String imageName = resourceUri.replaceFirst("^https?://", "");

            String severity = vuln.path("effectiveSeverity").asText(vuln.path("severity").asText("MEDIUM"));

            // CVE id: noteName tail (providers/.../notes/CVE-XXXX) or shortDescription
            String cveId = vuln.path("shortDescription").asText("");
            String noteName = occ.path("noteName").asText("");
            if ((cveId == null || cveId.isBlank()) && noteName.contains("/notes/")) {
                cveId = noteName.substring(noteName.lastIndexOf("/notes/") + 7);
            }

            String description = occ.path("vulnerability").path("longDescription").asText(
                    vuln.path("shortDescription").asText(""));

            // First affected package
            String packageName = "";
            String version = "";
            JsonNode pkgIssues = vuln.path("packageIssue");
            if (pkgIssues.isArray() && !pkgIssues.isEmpty()) {
                JsonNode first = pkgIssues.get(0);
                packageName = first.path("affectedPackage").asText("");
                version = first.path("affectedVersion").path("fullName").asText(
                        first.path("affectedVersion").path("name").asText(""));
            }

            result.add(new GcpContainerVulnerabilityDto(
                    imageName, severity, packageName, version, cveId, description));
        } catch (Exception e) {
            log.warn("[GcpSecurity] Failed to parse vulnerability occurrence: {}", e.getMessage());
        }
    }

    private int severityRank(String severity) {
        if (severity == null) return 4;
        switch (severity.toUpperCase()) {
            case "CRITICAL": return 0;
            case "HIGH":     return 1;
            case "MEDIUM":   return 2;
            case "LOW":      return 3;
            default:         return 4;
        }
    }

    // -------------------------------------------------------------------------
    // Security score
    // -------------------------------------------------------------------------

    public int calculateSecurityScore(List<GcpSecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) return 100;
        Map<String, Long> counts = findings.stream()
                .collect(Collectors.groupingBy(GcpSecurityFinding::getSeverity, Collectors.counting()));
        long weighted = (counts.getOrDefault("CRITICAL", 0L) * 5)
                + (counts.getOrDefault("HIGH", 0L) * 2)
                + (counts.getOrDefault("MEDIUM", 0L));
        return Math.max(0, (int) Math.round(100.0 / (1 + 0.1 * weighted)));
    }

    /** Same formula as {@link #calculateSecurityScore(List)}, from exact severity totals. */
    public int calculateSecurityScore(Map<String, Long> severityTotals) {
        if (severityTotals == null || severityTotals.isEmpty()) return 100;
        long weighted = (severityTotals.getOrDefault("CRITICAL", 0L) * 5)
                + (severityTotals.getOrDefault("HIGH", 0L) * 2)
                + (severityTotals.getOrDefault("MEDIUM", 0L));
        return Math.max(0, (int) Math.round(100.0 / (1 + 0.1 * weighted)));
    }

    /**
     * Exact totals for the project's last scan, from Redis then the small DB digest row. Empty when
     * none has been recorded. Never loads the findings list.
     */
    public Optional<GcpSecurityDigest> getSecurityDigest(String gcpProjectId) {
        try {
            Optional<GcpSecurityDigest> cached = redisCacheService.get(
                    REDIS_DIGEST_PREFIX + gcpProjectId, new TypeReference<GcpSecurityDigest>() {});
            if (cached.isPresent()) {
                return cached;
            }
            String key = digestDbKey(gcpProjectId);
            Long length = storedJsonLength(key);
            if (length == null || length > 1_000_000L) {
                return Optional.empty();
            }
            return cachedDataRepository.findById(key)
                    .map(row -> {
                        try {
                            return objectMapper.readValue(row.getJsonData(), GcpSecurityDigest.class);
                        } catch (Exception e) {
                            log.warn("[GcpSecurity] Could not parse digest for project={}: {}",
                                    gcpProjectId, e.getMessage());
                            return null;
                        }
                    });
        } catch (Exception e) {
            log.warn("[GcpSecurity] Could not read digest for project={}: {}", gcpProjectId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String digestDbKey(String gcpProjectId) {
        return "GCP_SECURITY::" + gcpProjectId + "::DIGEST";
    }

    // -------------------------------------------------------------------------
    // Force refresh (called by scheduler and controller)
    // -------------------------------------------------------------------------

    public CompletableFuture<List<GcpSecurityFinding>> refreshSecurityFindings(String gcpProjectId) {
        log.info("🔄 [GcpSecurity] Force refresh triggered for project={}", gcpProjectId);
        redisCacheService.evict(REDIS_SECURITY_PREFIX + gcpProjectId);
        // Also trigger a fresh Prowler scan so merged results are up-to-date
        triggerProwlerScan(gcpProjectId);
        return getSecurityFindings(gcpProjectId, true);
    }

    // -------------------------------------------------------------------------
    // Cache clear (exposed via controller)
    // -------------------------------------------------------------------------

    public void clearCacheForProject(String gcpProjectId) {
        redisCacheService.evict(REDIS_SECURITY_PREFIX   + gcpProjectId);
        redisCacheService.evict(REDIS_DIGEST_PREFIX     + gcpProjectId);
        failures.remove(gcpProjectId);
        redisCacheService.evict(REDIS_IAM_DRIFT_PREFIX  + gcpProjectId);
        redisCacheService.evict(REDIS_CONTAINER_PREFIX  + gcpProjectId);
        log.info("🗑️ [GcpSecurity] Cleared all security caches for project={}", gcpProjectId);
    }

    // -------------------------------------------------------------------------
    // Prowler helpers
    // -------------------------------------------------------------------------

    /** Gap A/E: trigger Prowler GCP scan for this project. */
    public void triggerProwlerScan(String gcpProjectId) {
        Map<String, Object> status = prowlerService.getGcpScanStatus(gcpProjectId);
        if ("RUNNING".equals(status.get("status"))) {
            log.info("[GcpSecurity] Prowler scan already running for project={} — skipping trigger", gcpProjectId);
            return;
        }
        log.info("[GcpSecurity] Triggering Prowler scan for project={}", gcpProjectId);
        prowlerService.triggerGcpScanAsync(gcpProjectId, GCP_PROWLER_FRAMEWORKS);
    }

    /** Gap E: map cached Prowler GCP findings → GcpSecurityFinding. */
    private int mapProwlerFindings(String gcpProjectId, java.util.function.Consumer<GcpSecurityFinding> sink) {
        List<ProwlerFinding> raw = prowlerService.getCachedGcpFindings(gcpProjectId);
        int[] count = {0};
        java.util.function.Consumer<GcpSecurityFinding> results = f -> {
            count[0]++;
            sink.accept(f);
        };
        for (ProwlerFinding pf : raw) {
            if (!"FAIL".equalsIgnoreCase(pf.getStatus())) continue;
            String severity = pf.getSeverity() != null
                    ? pf.getSeverity().substring(0, 1).toUpperCase() + pf.getSeverity().substring(1).toLowerCase()
                    : "Medium";
            String category = pf.getServiceName() != null ? pf.getServiceName().toUpperCase() : "UNKNOWN";

            Map<String, List<String>> compliance = pf.getCompliance();
            if (compliance != null && !compliance.isEmpty()) {
                for (Map.Entry<String, List<String>> entry : compliance.entrySet()) {
                    String controlId = (entry.getValue() != null && !entry.getValue().isEmpty())
                            ? String.join(", ", entry.getValue()) : pf.getCheckId();
                    results.accept(new GcpSecurityFinding(
                            category,
                            pf.getDescription(),
                            severity,
                            pf.getResourceId(),
                            entry.getKey() + " / " + controlId));
                }
            } else {
                results.accept(new GcpSecurityFinding(
                        category,
                        pf.getDescription(),
                        severity,
                        pf.getResourceId(),
                        "Prowler / " + pf.getCheckId()));
            }
        }
        return count[0];
    }

    // -------------------------------------------------------------------------
    // Background refresh (called on staleness detection)
    // -------------------------------------------------------------------------

    private void triggerBackgroundRefresh(String gcpProjectId) {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[GcpSecurity] Background staleness refresh starting for project={}", gcpProjectId);
                triggerProwlerScan(gcpProjectId);
                // Re-fetch SCC so DB LATEST is updated
                getSecurityFindings(gcpProjectId, true).join();
                log.info("[GcpSecurity] Background refresh complete for project={}", gcpProjectId);
            } catch (Exception e) {
                log.error("[GcpSecurity] Background refresh failed for project={}: {}", gcpProjectId, e.getMessage());
            }
        }, executor);
    }

    // -------------------------------------------------------------------------
    // SCC fetch
    // -------------------------------------------------------------------------

    /**
     * Outcome of one SCC read.
     *
     * <p>
     * The point of this type is the {@code succeeded} flag. {@code fetchFromScc} used to return a
     * bare list and swallow every failure, so "this project has no active findings" and "we could
     * not reach Security Command Center" arrived as the same empty list — and the caller had to
     * guess. Guessing wrongly in one direction tells a customer they are secure during an API
     * outage; guessing wrongly in the other keeps showing findings they have fixed. Neither is
     * acceptable, so the two are now distinguishable.
     */
    private record SccScan(GcpSecurityFindingCollector collector, boolean succeeded,
                           boolean stoppedEarly, Map<String, Long> exactSeverityTotals) {
        static SccScan failed(int maxFindings) {
            return new SccScan(new GcpSecurityFindingCollector(maxFindings), false, false, null);
        }
    }

    private static Map<String, Long> prowlerSeverityTotals(GcpSecurityFindingCollector collector,
                                                           Map<String, Long> sccOnly) {
        Map<String, Long> out = new HashMap<>(collector.severityTotalsSnapshot());
        sccOnly.forEach((k, v) -> out.merge(k, -v, Long::sum));
        out.values().removeIf(v -> v <= 0);
        return out;
    }

    /**
     * Digest for the merged SCC + Prowler result. When the SCC fetch stopped early, exact SCC totals
     * (GroupFindings) plus the Prowler part are used; if those are unavailable the digest is marked
     * approximate (counts are lower bounds). Complete fetches keep the previous behaviour.
     */
    private GcpSecurityDigest buildDigest(SccScan scan, GcpSecurityFindingCollector collector,
                                          Map<String, Long> prowlerPart, int retained) {
        if (!scan.succeeded() || !scan.stoppedEarly()) {
            return collector.digest(retained);
        }
        Map<String, Long> exact = scan.exactSeverityTotals();
        if (exact == null) {
            return collector.digest(retained, true, true, null);
        }
        Map<String, Long> combinedTotals = new HashMap<>(exact);
        prowlerPart.forEach((k, v) -> combinedTotals.merge(k, v, Long::sum));
        long exactTotal = combinedTotals.values().stream().mapToLong(Long::longValue).sum();
        return collector.digest(retained, exactTotal > retained, false, combinedTotals);
    }

    /** True when a snapshot timestamp is older than {@link #SNAPSHOT_MAX_AGE_HOURS}. */
    private boolean isStale(LocalDateTime lastUpdated) {
        // Absent timestamp counts as stale: an un-stamped row is of unknown age, and on a security
        // page unknown age has to mean "go and look" rather than "assume it is current".
        if (lastUpdated == null) {
            return true;
        }
        return lastUpdated.isBefore(LocalDateTime.now().minusHours(SNAPSHOT_MAX_AGE_HOURS));
    }

    /** The last archived list regardless of age, for use when a live scan could not run. */
    private List<GcpSecurityFinding> lastKnownSnapshot(String dbKeyLatest, String gcpProjectId) {
        try {
            if (isOversizedSnapshot(dbKeyLatest, gcpProjectId)) {
                return null;
            }
            Optional<CachedData> snap = cachedDataRepository.findById(dbKeyLatest);
            if (snap.isPresent()) {
                return objectMapper.readValue(snap.get().getJsonData(), new TypeReference<>() {});
            }
        } catch (Exception e) {
            rethrowIfOutOfMemory(e, gcpProjectId, "reading the previous snapshot");
            log.warn("[GcpSecurity] Could not read the previous snapshot for project={}: {}",
                    gcpProjectId, e.getMessage());
        }
        return null;
    }

    private SccScan fetchFromScc(String gcpProjectId) {
        GcpSecurityFindingCollector collector = new GcpSecurityFindingCollector(maxFindings);
        Optional<SecurityCenterClient> clientOpt = gcpClientProvider.getSecurityCenterV2Client(gcpProjectId);
        if (clientOpt.isEmpty()) {
            // No client is a failure, not an empty account — usually a missing credential or a
            // disabled API, and reporting it as "no findings" would be a false all-clear.
            log.warn("[GcpSecurity] SCC V2 client not available for project={}", gcpProjectId);
            return SccScan.failed(maxFindings);
        }
        try (SecurityCenterClient client = clientOpt.get()) {
            String parent = String.format("projects/%s/sources/-", gcpProjectId);
            GcpSccFindingPager.Result result = GcpSccFindingPager.fetch(gcpProjectId,
                    (filter, token, size) -> {
                        ListFindingsRequest.Builder request = ListFindingsRequest.newBuilder()
                                .setParent(parent)
                                .setFilter(filter)
                                .setPageSize(size);
                        if (token != null && !token.isBlank()) {
                            request.setPageToken(token);
                        }
                        // getPage() loads exactly one page; iterateAll() would follow on lazily.
                        ListFindingsResponse response = client.listFindings(request.build()).getPage().getResponse();
                        List<GcpSecurityFinding> mapped = new ArrayList<>(response.getListFindingsResultsCount());
                        for (ListFindingsResponse.ListFindingsResult r : response.getListFindingsResultsList()) {
                            mapped.add(mapToDto(r.getFinding()));
                        }
                        return new GcpSccFindingPager.Page(mapped, response.getNextPageToken());
                    },
                    collector::add, sccPageSize, sccMaxPages, maxFindings);
            log.info("[GcpSecurity] Fetched {} active SCC findings in {} page(s) for project={}",
                    result.fetched(), result.pagesFetched(), gcpProjectId);
            Map<String, Long> exactTotals = result.stoppedEarly() ? fetchExactSeverityTotals(client, parent, gcpProjectId) : null;
            return new SccScan(collector, true, result.stoppedEarly(), exactTotals);
        } catch (Exception e) {
            rethrowIfOutOfMemory(e, gcpProjectId, "fetching SCC findings");
            log.error("[GcpSecurity] SCC fetch failed for project={}: {}", gcpProjectId, e.getMessage(), e);
            return SccScan.failed(maxFindings);
        }
    }

    /**
     * One GroupFindings call (state=ACTIVE, group_by severity) for exact per-severity totals.
     * Returns null on any failure (never fails the scan); OOM is rethrown.
     */
    private Map<String, Long> fetchExactSeverityTotals(SecurityCenterClient client, String parent, String gcpProjectId) {
        try {
            GroupFindingsRequest request = GroupFindingsRequest.newBuilder()
                    .setParent(parent)
                    .setFilter("state=\"ACTIVE\"")
                    .setGroupBy("severity")
                    .setPageSize(100)
                    .build();
            Map<String, Long> totals = new HashMap<>();
            for (GroupResult group : client.groupFindings(request).iterateAll()) {
                com.google.protobuf.Value v = group.getPropertiesMap().get("severity");
                String name = null;
                if (v != null) {
                    if (v.getKindCase() == com.google.protobuf.Value.KindCase.STRING_VALUE) {
                        name = v.getStringValue().toUpperCase();
                    } else if (v.getKindCase() == com.google.protobuf.Value.KindCase.NUMBER_VALUE) {
                        Finding.Severity sev = Finding.Severity.forNumber((int) v.getNumberValue());
                        name = sev == null ? null : sev.name();
                    }
                }
                if (name == null || name.isBlank() || "SEVERITY_UNSPECIFIED".equals(name)) {
                    name = "UNKNOWN";
                }
                totals.merge(name, group.getCount(), Long::sum);
            }
            return totals;
        } catch (Exception e) {
            rethrowIfOutOfMemory(e, gcpProjectId, "grouping SCC findings by severity");
            log.warn("[GcpSecurity] GroupFindings failed for project={}: {}; totals will be a lower bound",
                    gcpProjectId, e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // DB archiving — Gap B: LATEST + dated history
    // -------------------------------------------------------------------------

    private void archiveFindings(String gcpProjectId, List<GcpSecurityFinding> findings, GcpSecurityDigest digest,
                                  String dbKeyLatest, String dbKeyDate) {
        try {
            String json = objectMapper.writeValueAsString(findings);
            if (json.length() > maxSnapshotBytes) {
                log.warn("[GcpSecurity] Snapshot for project={} is {} chars, over xamops.gcp.security.max-snapshot-bytes={}; "
                        + "not archiving it", gcpProjectId, json.length(), maxSnapshotBytes);
                return;
            }
            // Same clock the staleness check compares against. These were stamped in
            // Asia/Kolkata while isStale() reads LocalDateTime.now(), so on a UTC deployment every
            // snapshot looked 5.5 hours younger than it was and aged out that much late.
            LocalDateTime now = LocalDateTime.now();
            // LATEST — fast fallback on restart
            saveToDb(dbKeyLatest, json, now);
            // Dated — one row per day, audit trail
            saveToDb(dbKeyDate, json, now);
            // Exact totals, small enough to read without risk
            saveToDb(digestDbKey(gcpProjectId), objectMapper.writeValueAsString(digest), now);
            log.info("💾 [GcpSecurity] Archived {} findings (LATEST + {}) for project={}",
                    findings.size(), LocalDate.now(), gcpProjectId);
        } catch (Exception e) {
            rethrowIfOutOfMemory(e, gcpProjectId, "archiving findings");
            log.warn("[GcpSecurity] Failed to archive findings for project={}: {}", gcpProjectId, e.getMessage());
        }
    }

    private void saveToDb(String key, String json, LocalDateTime now) {
        try {
            // An existing row over the limit is removed rather than loaded: save() on an existing
            // key reads the old payload first, which is exactly the load being avoided.
            Long existing = storedJsonLength(key);
            if (existing != null && existing == SIZE_UNKNOWN) {
                // Cannot tell whether findById below would load an oversized payload: skip the save.
                return;
            }
            if (existing != null && existing > maxSnapshotBytes) {
                log.warn("[GcpSecurity] Replacing oversized snapshot key={} ({} bytes/chars)", key, existing);
                cachedDataRepository.deleteOversizedByCacheKey(key);
            }
            CachedData entity = cachedDataRepository.findById(key).orElse(new CachedData());
            entity.setCacheKey(key);
            entity.setJsonData(json);
            entity.setLastUpdated(now);
            cachedDataRepository.save(entity);
        } catch (Exception e) {
            log.warn("[GcpSecurity] DB save failed for key={}: {}", key, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Size guard, back-off and OOM handling
    // -------------------------------------------------------------------------

    /** Returned by {@link #storedJsonLength} when the size query failed. Larger than any limit. */
    private static final long SIZE_UNKNOWN = Long.MAX_VALUE;
    private final java.util.Set<String> sizeQueryWarned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Real stored size of a cached_data row (large-object bytes for a legacy OID reference, text
     * length otherwise), null when there is no row. If the query fails (for example no privilege on
     * pg_largeobject) it logs one WARN per key until the next success and returns
     * {@link #SIZE_UNKNOWN}, which callers treat as "too large to load": the snapshot is skipped and
     * a live scan runs, rather than risking the load that ran the JVM out of memory.
     */
    private Long storedJsonLength(String key) {
        try {
            Long length = LegacyLobTextType.storedLength(jdbcTemplate, "cached_data", "json_data", "cache_key", key);
            sizeQueryWarned.remove(key);
            return length;
        } catch (Exception e) {
            rethrowIfOutOfMemory(e, key, "measuring a stored snapshot");
            if (sizeQueryWarned.add(key)) {
                log.warn("[GcpSecurity] Could not measure stored snapshot key={} ({}); treating it as too large "
                        + "to load until the size query works", key, e.getMessage());
            }
            return SIZE_UNKNOWN;
        }
    }

    /** True (with a WARN) when the stored snapshot is over the limit and must not be loaded. */
    private boolean isOversizedSnapshot(String dbKey, String gcpProjectId) {
        Long length = storedJsonLength(dbKey);
        if (length != null && length == SIZE_UNKNOWN) {
            return true; // size could not be determined (already logged): do not risk loading it
        }
        if (length != null && length > maxSnapshotBytes) {
            log.warn("[GcpSecurity] Ignoring oversized snapshot ({} chars > xamops.gcp.security.max-snapshot-bytes={}) "
                    + "for project={}; it will be replaced by the next successful scan",
                    length, maxSnapshotBytes, gcpProjectId);
            return true;
        }
        return false;
    }

    /**
     * Rethrows the Error when {@code e} wraps an {@link OutOfMemoryError} (Hibernate, Jackson and
     * CompletableFuture all wrap), after an ERROR log naming the project. Otherwise returns.
     */
    private void rethrowIfOutOfMemory(Throwable e, String gcpProjectId, String activity) {
        for (Throwable t = e; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof OutOfMemoryError oom) {
                log.error("[GcpSecurity] OutOfMemoryError while {} for project={}", activity, gcpProjectId);
                throw oom;
            }
        }
    }

    private void recordFailure(String gcpProjectId) {
        Instant now = Instant.now();
        FailureState next = failures.merge(gcpProjectId, new FailureState(1, now, now), (old, ignored) ->
                new FailureState(old.consecutive() + 1, now, now));
        long exponent = Math.min(next.consecutive(), Math.max(1, backoffMaxConsecutiveFailures)) - 1;
        long delay = Math.min(backoffMaxSeconds, backoffInitialSeconds * (1L << Math.min(exponent, 30)));
        long jitter = (long) (delay * 0.1 * java.util.concurrent.ThreadLocalRandom.current().nextDouble());
        failures.put(gcpProjectId, new FailureState(next.consecutive(), now, now.plusSeconds(delay + jitter)));
        log.warn("[GcpSecurity] project={} failed {} time(s) in a row; next attempt in ~{}s",
                gcpProjectId, next.consecutive(), delay + jitter);
    }

    /**
     * While a project is in back-off, answers from Redis (or empty) without touching the DB or SCC.
     * Returns null when the caller should go ahead.
     */
    private List<GcpSecurityFinding> serveIfBackedOff(String gcpProjectId, boolean forceRefresh, String redisKey) {
        FailureState state = failures.get(gcpProjectId);
        if (state == null) {
            return null;
        }
        Instant now = Instant.now();
        if (!now.isBefore(state.nextAttempt())) {
            return null;
        }
        if (forceRefresh && !now.isBefore(state.lastAttempt().plusSeconds(FORCE_REFRESH_MIN_INTERVAL_SECONDS))) {
            return null;
        }
        log.info("[GcpSecurity] project={} in back-off for another {}s after {} consecutive failures; "
                        + "serving cached/empty", gcpProjectId,
                java.time.Duration.between(now, state.nextAttempt()).toSeconds(), state.consecutive());
        Optional<List<GcpSecurityFinding>> cached = redisCacheService.get(redisKey, new TypeReference<>() {});
        return cached.orElseGet(Collections::emptyList);
    }

    // -------------------------------------------------------------------------
    // DTO mapping
    // -------------------------------------------------------------------------

    private GcpSecurityFinding mapToDto(Finding finding) {
        return new GcpSecurityFinding(
                finding.getCategory(),
                finding.getDescription(),
                finding.getSeverity().toString(),
                finding.getResourceName(),
                mapToSoc2Control(finding.getCategory()));
    }

    private String mapToSoc2Control(String category) {
        if (category == null || category.isBlank()) return null;
        switch (category.toUpperCase()) {
            case "IAM_ANOMALOUS_GRANT":
            case "ADMIN_SERVICE_ACCOUNT":
            case "OVER_PRIVILEGED_SERVICE_ACCOUNT_USER":
            case "PRIMITIVE_ROLES_USED":
            case "KMS_PROJECT_HAS_OWNER":
            case "MFA_NOT_ENFORCED":
                return "SOC2 CC6.1";
            case "OPEN_FIREWALL":
            case "UNRESTRICTED_SSH":
            case "UNRESTRICTED_RDP":
            case "FIREWALL_RULE_ALLOWS_ALL":
                return "SOC2 CC6.6";
            case "BUCKET_POLICY_ONLY_DISABLED":
            case "PUBLICLY_ACCESSIBLE_BUCKET":
            case "OBJECT_VERSIONING_DISABLED":
            case "NON_CMEK_DISKS":
                return "SOC2 CC6.7";
            case "SERVICE_ACCOUNT_KEY_NOT_ROTATED":
            case "KMS_KEY_NOT_ROTATED":
                return "SOC2 CC6.8";
            case "AUDIT_LOGGING_DISABLED":
            case "BUCKET_LOGGING_DISABLED":
            case "FLOW_LOGS_DISABLED":
            case "LOG_NOT_EXPORTED":
            case "CLOUD_AUDIT_LOGGING_DISABLED":
                return "SOC2 CC7.1";
            default:
                break;
        }
        String upper = category.toUpperCase();
        if (upper.contains("IAM") || upper.contains("IDENTITY") || upper.contains("PRIVILEGE")
                || upper.contains("ADMIN") || upper.contains("MFA") || upper.contains("ACCESS_CONTROL"))
            return "SOC2 CC6.1";
        if (upper.contains("FIREWALL") || upper.contains("NETWORK") || upper.contains("UNRESTRICTED")
                || upper.contains("OPEN_PORT") || upper.contains("EXPOSED"))
            return "SOC2 CC6.6";
        if (upper.contains("ENCRYPT") || upper.contains("PUBLIC_BUCKET") || upper.contains("PUBLIC_ACCESS")
                || upper.contains("TLS") || upper.contains("SSL") || upper.contains("CMEK"))
            return "SOC2 CC6.7";
        if (upper.contains("KEY_ROTATION") || upper.contains("KEY_NOT_ROTATED") || upper.contains("SECRET")
                || upper.contains("KMS") || upper.contains("SERVICE_ACCOUNT_KEY"))
            return "SOC2 CC6.8";
        if (upper.contains("LOG") || upper.contains("AUDIT") || upper.contains("MONITOR")
                || upper.contains("FLOW_LOG") || upper.contains("TRAIL"))
            return "SOC2 CC7.1";
        return null;
    }
}
