// src/main/java/com/xammer/cloud/service/gcp/GcpCloudGuardService.java
package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
// Correct Compute Engine Region import
import com.google.cloud.compute.v1.Region;
// *** REMOVE THIS INCORRECT IMPORT ***
// import com.google.cloud.compute.v1.RegionName;
// *** ADD THIS IMPORT FOR THE REQUEST BUILDER ***
import com.google.cloud.compute.v1.GetRegionRequest;
// Other imports remain the same
import com.google.cloud.compute.v1.NetworksClient;
import com.google.cloud.compute.v1.Quota;
import com.google.cloud.compute.v1.RegionsClient;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.gcp.GcpAlertsResponse;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.AlertEmailBuilder;
import com.xammer.cloud.service.AlertNotificationDeduplicator;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpCloudGuardService {

    private final CloudAccountRepository cloudAccountRepository;
    private final GcpClientProvider gcpClientProvider;
    private final RedisCacheService redisCache;
    private final GcpDataService gcpDataService;
    private final GcpCostService gcpCostService;
    private final GcpSecurityService gcpSecurityService;
    private final EmailService emailService;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final AlertEmailBuilder alertEmailBuilder;
    private final AlertNotificationDeduplicator deduplicator;

    // Define Quota details
    private static final String NETWORK_QUOTA_NAME = "NETWORKS";
    private static final double NETWORK_QUOTA_THRESHOLD = 75.0;
    /**
     * Versioned because the cached payload shape changed from a bare {@code List<AlertDto>} to a
     * {@link GcpAlertsResponse}. Bumping the key rather than relying on a deserialisation failure
     * means the transition is a clean miss-and-rebuild instead of a warning per project per read.
     */
    private static final String DB_CACHE_PREFIX = "GCP_ALERTS_V2::";

    /** Redis key prefix, versioned for the same reason as {@link #DB_CACHE_PREFIX}. */
    private static final String REDIS_CACHE_PREFIX = "gcpAlerts-v2-";

    /** Redis lifetime for the alerts list, in minutes. */
    private static final int ALERTS_CACHE_MINUTES = 60;

    /**
     * How old the DB snapshot may be and still be served.
     *
     * <p>
     * Matched to the Redis TTL deliberately. The DB tier exists to survive a Redis restart, not to
     * outlive it — and because this row has no expiry column, serving it unconditionally while also
     * re-warming Redis meant a stale alert list renewed its own lifetime on every read and never
     * aged out. Alerts are a "what needs attention now" surface, so an hour is already generous.
     */
    private static final int SNAPSHOT_MAX_AGE_MINUTES = ALERTS_CACHE_MINUTES;

    @Autowired
    public GcpCloudGuardService(
            CloudAccountRepository cloudAccountRepository,
            GcpClientProvider gcpClientProvider,
            RedisCacheService redisCache,
            GcpDataService gcpDataService,
            GcpCostService gcpCostService,
            GcpSecurityService gcpSecurityService,
            EmailService emailService,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            UserRepository userRepository,
            AlertEmailBuilder alertEmailBuilder,
            AlertNotificationDeduplicator deduplicator
    ) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.gcpClientProvider = gcpClientProvider;
        this.redisCache = redisCache;
        this.gcpDataService = gcpDataService;
        this.gcpCostService = gcpCostService;
        this.gcpSecurityService = gcpSecurityService;
        this.emailService = emailService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.alertEmailBuilder = alertEmailBuilder;
        this.deduplicator = deduplicator;
    }

    private void saveToDb(String dbKey, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            // Same clock the staleness check above compares against. Stamping Asia/Kolkata while
            // reading LocalDateTime.now() made every snapshot look 5.5h younger on a UTC host.
            entity.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to save GCP alerts DB snapshot for key {}: {}", dbKey, e.getMessage());
        }
    }

    private CloudAccount getAccount(String accountId) {
        // Use findByProviderAccountId which checks GCP Project ID as well
        CloudAccount account = cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));

        // Force initialization of the Client proxy if needed (optional, depends on usage)
        if (account.getClient() != null) {
            try {
                account.getClient().getEmail(); // Access a field to trigger initialization
            } catch (Exception e) {
                log.warn("Could not initialize client proxy for account {}", accountId, e);
            }
        }
        return account;
    }

    /**
     * Read the alert list for a project. Never sends email.
     *
     * <p>
     * This is the overload the API controller uses. Reading a page must not notify anybody: the
     * digest used to be sent from inside this method unconditionally, which made a plain
     * {@code GET /alerts} a mutating request and meant every press of "Refresh Alerts" mailed every
     * user of the tenant. Support staff reproducing a customer's complaint were themselves
     * generating the complaint.
     *
     * @see #getAlerts(String, boolean, boolean)
     */
    @Transactional(readOnly = true) // Add transactional annotation
    public CompletableFuture<GcpAlertsResponse> getAlerts(String gcpProjectId, boolean forceRefresh) {
        return getAlerts(gcpProjectId, forceRefresh, false);
    }

    /**
     * Read the alert list, optionally emailing a digest when the content has changed.
     *
     * @param notify when true, and only then, a digest may be sent. Reserved for the nightly
     *     scheduler. Still subject to the fingerprint de-duplication, so an unchanged list sends
     *     nothing.
     */
    @Transactional(readOnly = true) // Add transactional annotation
    public CompletableFuture<GcpAlertsResponse> getAlerts(String gcpProjectId, boolean forceRefresh, boolean notify) {
        String cacheKey = REDIS_CACHE_PREFIX + gcpProjectId;
        String dbKey = DB_CACHE_PREFIX + gcpProjectId;
        log.info("🔍 Fetching alerts for GCP project: {} (forceRefresh={}, notify={})",
                gcpProjectId, forceRefresh, notify);

        if (!forceRefresh) {
            // 1. Try Redis
            Optional<GcpAlertsResponse> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                log.info("✅ Returning Redis-cached GCP alerts for project {}", gcpProjectId);
                GcpAlertsResponse cached = cachedData.get();
                cached.setFromCache(true);
                return CompletableFuture.completedFuture(cached);
            }

            // 2. Try DB snapshot, but only while it is still current enough to serve.
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    LocalDateTime stamped = dbSnapshot.get().getLastUpdated();
                    // No timestamp counts as too old: a row of unknown age cannot be assumed
                    // current on a page whose whole purpose is telling someone what is wrong now.
                    boolean tooOld = stamped == null
                            || stamped.isBefore(LocalDateTime.now().minusMinutes(SNAPSHOT_MAX_AGE_MINUTES));
                    if (tooOld) {
                        log.info("GCP alerts DB snapshot for project {} is older than {} min — ignoring it "
                                + "and rebuilding live", gcpProjectId, SNAPSHOT_MAX_AGE_MINUTES);
                    } else {
                        GcpAlertsResponse dbResult = objectMapper.readValue(
                                dbSnapshot.get().getJsonData(), GcpAlertsResponse.class);
                        dbResult.setFromCache(true);
                        if (dbResult.getGeneratedAt() == null) {
                            dbResult.setGeneratedAt(stamped);
                        }
                        log.info("✅ Returning DB-cached GCP alerts for project {} — re-warming Redis", gcpProjectId);
                        redisCache.put(cacheKey, dbResult, ALERTS_CACHE_MINUTES);
                        return CompletableFuture.completedFuture(dbResult);
                    }
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for GCP alerts {}: {}", gcpProjectId, e.getMessage());
            }
        }

        CloudAccount account = getAccount(gcpProjectId);

        // Eagerly fetch all sub-user emails before async operations
        List<String> recipientEmails = Collections.emptyList();
        try {
            if (account.getClient() != null) {
                recipientEmails = userRepository.findByClientId(account.getClient().getId()).stream()
                        .map(u -> (u.getEmail() != null && !u.getEmail().isBlank()) ? u.getEmail()
                                : (u.getUsername() != null && u.getUsername().contains("@") ? u.getUsername() : null))
                        .filter(e -> e != null)
                        .distinct()
                        .collect(Collectors.toList());
            } else {
                log.warn("Client is null for account {}", gcpProjectId);
            }
        } catch (Exception e) {
            log.error("Error fetching recipient emails for account {}: {}", gcpProjectId, e.getMessage(), e);
        }
        final List<String> recipients = recipientEmails;

        CompletableFuture<CheckOutcome> quotasFuture = checkQuotaAlerts(account);
        CompletableFuture<CheckOutcome> anomaliesFuture = checkCostAnomalies(account, forceRefresh);
        CompletableFuture<CheckOutcome> securityFuture = checkSecurityFindings(account, forceRefresh);

        return CompletableFuture.allOf(quotasFuture, anomaliesFuture, securityFuture)
                .thenApply(v -> {
                    List<CheckOutcome> outcomes =
                            List.of(quotasFuture.join(), anomaliesFuture.join(), securityFuture.join());

                    List<AlertDto> alerts = new ArrayList<>();
                    List<String> failures = new ArrayList<>();
                    for (CheckOutcome outcome : outcomes) {
                        alerts.addAll(outcome.alerts());
                        if (outcome.failed()) {
                            failures.add(outcome.reason());
                        }
                    }

                    GcpAlertsResponse response =
                            new GcpAlertsResponse(alerts, LocalDateTime.now(), false);
                    response.markDegraded(failures);

                    // Send HTML digest only when alert content changes, and only when the caller
                    // is entitled to notify at all.
                    if (!notify) {
                        log.debug("[Dedup] Notification suppressed for project {} — this is a read, not the "
                                + "scheduled digest run", gcpProjectId);
                    } else if (response.isDegraded()) {
                        // Do not mail a list we know to be incomplete. A digest assembled from a
                        // partial scan is worse than no digest: it reads as the full picture, and
                        // its fingerprint would then suppress the correct digest once the source
                        // recovers, because the dedup marker only remembers what was last sent.
                        log.warn("[Dedup] Skipping GCP alert digest for project {} — the scan was degraded ({})",
                                gcpProjectId, String.join("; ", response.getDegradedReasons()));
                    } else if (!recipients.isEmpty() && !alerts.isEmpty()) {
                        String fingerprint = AlertNotificationDeduplicator.computeFingerprint(alerts);
                        if (deduplicator.gcpDigestAlreadySentForContent(gcpProjectId, fingerprint)) {
                            log.info("[Dedup] Same alerts already notified for project {} — skipping email", gcpProjectId);
                        } else {
                            String accountName = account.getAccountName() != null ? account.getAccountName() : gcpProjectId;
                            String htmlBody = alertEmailBuilder.buildAlertDigestEmail(alerts, accountName, gcpProjectId);
                            String subject = String.format("[XamOps] GCP Alert Digest — %d alert%s for %s",
                                    alerts.size(), alerts.size() == 1 ? "" : "s", accountName);
                            for (String email : recipients) {
                                try {
                                    emailService.sendHtmlEmail(email, subject, htmlBody);
                                    log.info("Sent GCP alert digest email to {}", email);
                                } catch (Exception e) {
                                    log.error("Failed to send GCP alert digest email to {}: {}", email, e.getMessage());
                                }
                            }
                            deduplicator.markGcpDigestSent(gcpProjectId, fingerprint);
                        }
                    } else if (!alerts.isEmpty()) {
                        log.warn("No recipient emails configured for account {}, cannot send alert emails.", gcpProjectId);
                    }

                    // Only cache a complete result. Persisting a degraded one would hand the next
                    // reader an incomplete list for the full TTL, with nothing to distinguish it
                    // from a good one.
                    if (response.isDegraded()) {
                        log.warn("Not caching GCP alerts for project {} — the scan was degraded", gcpProjectId);
                    } else {
                        redisCache.put(cacheKey, response, ALERTS_CACHE_MINUTES);
                        saveToDb(dbKey, response);
                    }

                    log.info("✅ Returning {} GCP alert rows ({} underlying findings) for project {}",
                            alerts.size(), response.getTotalFindings(), gcpProjectId);
                    return response;
                })
                .exceptionally(ex -> {
                    log.error("❌ Error fetching GCP alerts for project {}: {}", gcpProjectId, ex.getMessage(), ex);
                    // Reported as degraded rather than as an empty list. An empty list renders as
                    // "All Clear!", which is precisely the wrong thing to tell someone when the
                    // truth is that the check did not run.
                    return GcpAlertsResponse.failed(
                            "The alert scan could not be completed: " + rootMessage(ex));
                });
    }

    /**
     * Outcome of one of the three alert sources.
     *
     * <p>
     * The reason this exists is that each sub-check used to swallow its exception and return
     * whatever it had accumulated, so a source that failed outright was indistinguishable from a
     * source that legitimately found nothing. Three silent failures produced an empty list and a
     * green "All Clear!" — a false all-clear on a page people rely on to tell them what is wrong.
     */
    private record CheckOutcome(List<AlertDto> alerts, boolean failed, String reason) {

        static CheckOutcome ok(List<AlertDto> alerts) {
            return new CheckOutcome(alerts, false, null);
        }

        /** Failed with nothing to show. */
        static CheckOutcome failed(String reason) {
            return new CheckOutcome(new ArrayList<>(), true, reason);
        }

        /** Produced something, but is known to be incomplete. */
        static CheckOutcome partial(List<AlertDto> alerts, String reason) {
            return new CheckOutcome(alerts, true, reason);
        }
    }

    /** Unwraps the CompletionException that {@code exceptionally} hands us, for a usable message. */
    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }

    private CompletableFuture<CheckOutcome> checkQuotaAlerts(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            List<AlertDto> alerts = new ArrayList<>();
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Quota alerts for GCP project {}", gcpProjectId);

            try {
                // 1. Get Network Quota Limit (Global)
                Optional<RegionsClient> regionsClientOpt = gcpClientProvider.getRegionsClient(gcpProjectId);
                if (regionsClientOpt.isEmpty()) {
                    log.warn("Could not get RegionsClient for project {}", gcpProjectId);
                    return CheckOutcome.failed("Quota usage could not be read: "
                            + "the Compute Engine API is unreachable for this project.");
                }
                double networkLimit = 0;
                try (RegionsClient regionsClient = regionsClientOpt.get()) {
                    // Need a valid region to query project-wide quotas
                    String sampleRegion = gcpDataService.getAllKnownRegions().stream().findFirst().orElse("us-central1");

                    // --- FIX: Build GetRegionRequest ---
                    GetRegionRequest regionRequest = GetRegionRequest.newBuilder()
                            .setProject(gcpProjectId)
                            .setRegion(sampleRegion)
                            .build();
                    Region regionInfo = regionsClient.get(regionRequest); // Use the request object
                    // --- END FIX ---

                    Optional<Quota> networkQuotaOpt = regionInfo.getQuotasList().stream()
                            .filter(q -> NETWORK_QUOTA_NAME.equals(q.getMetric()))
                            .findFirst();
                    if (networkQuotaOpt.isPresent()) {
                        networkLimit = networkQuotaOpt.get().getLimit();
                    } else {
                        log.warn("Could not find '{}' quota for project {}. Using default limit.", NETWORK_QUOTA_NAME, gcpProjectId);
                        networkLimit = 5; // Default VPC limit
                    }
                }

                // 2. Get Current Network Count
                Optional<NetworksClient> networksClientOpt = gcpClientProvider.getNetworksClient(gcpProjectId);
                if(networksClientOpt.isEmpty()) {
                    log.warn("Could not get NetworksClient for project {}", gcpProjectId);
                    return CheckOutcome.failed("Quota usage could not be read: "
                            + "the VPC network list is unreachable for this project.");
                }
                double networkUsage = 0;
                try (NetworksClient networksClient = networksClientOpt.get()) {
                    networkUsage = StreamSupport.stream(
                                    networksClient.list(gcpProjectId).iterateAll().spliterator(), false)
                            .count();
                }

                // 3. Compare and Create Alert
                if (networkLimit > 0) {
                    double utilization = (networkUsage / networkLimit) * 100.0;
                    if (utilization >= NETWORK_QUOTA_THRESHOLD) {
                        String status = determineQuotaStatus(networkUsage, networkLimit);
                        alerts.add(new AlertDto(
                                "quota-gcp-networks-global-" + account.getId(),
                                "VPC Network",
                                "VPC Network Limit Approaching",
                                String.format("Current VPC count: %.0f / %.0f (%.1f%%)", networkUsage, networkLimit, utilization),
                                status,
                                networkUsage,
                                networkLimit,
                                "QUOTA",
                                "global"
                        ));
                    }
                }
                log.info("Found {} GCP quota alerts for project {}", alerts.size(), gcpProjectId);

            } catch (Exception e) {
                log.error("Error checking GCP quotas for project {}: {}", gcpProjectId, e.getMessage(), e);
                return CheckOutcome.partial(alerts, "Quota usage could not be read: " + e.getMessage());
            }
            return CheckOutcome.ok(alerts);
        });
    }


    /**
     * @param forceRefresh passed through to the cost service rather than dropped. This used to call
     *     the single-argument {@code getHistoricalCostsSync(id)}, which hardcodes
     *     {@code forceRefresh=false}, so ANOMALY rows were served from the historical-cost cache no
     *     matter what the user pressed. Those rows drive the "critical" count in the KPI strip, so
     *     a resolved anomaly kept inflating the headline number after a refresh.
     */
    private CompletableFuture<CheckOutcome> checkCostAnomalies(CloudAccount account, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            List<AlertDto> alerts = new ArrayList<>();
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Cost Anomalies for GCP project {} (forceRefresh={})", gcpProjectId, forceRefresh);
            try {
                String symbol = currencySymbol(gcpCostService.getProjectCurrencyCode(gcpProjectId));
                List<GcpCostDto> historicalCosts =
                        gcpCostService.getHistoricalCostsSync(gcpProjectId, forceRefresh);
                AtomicInteger counter = new AtomicInteger(1);

                historicalCosts.stream()
                        .filter(GcpCostDto::isAnomaly)
                        .forEach(anomaly -> {
                            String uniqueId = String.format("anomaly-gcp-%s-%d", anomaly.getName(), counter.getAndIncrement());
                            alerts.add(new AlertDto(
                                    uniqueId,
                                    "Billing",
                                    "Cost Anomaly Detected",
                                    String.format("Potential cost anomaly detected for period %s. Cost: %s%.2f",
                                            anomaly.getName(), symbol, anomaly.getAmount()),
                                    "CRITICAL",
                                    anomaly.getAmount(),
                                    0,
                                    "ANOMALY",
                                    "Global"
                            ));
                        });
                log.info("Found {} GCP cost anomaly alerts for project {}", alerts.size(), gcpProjectId);
            } catch (Exception e) {
                log.error("Error checking GCP cost anomalies for project {}: {}", gcpProjectId, e.getMessage(), e);
                return CheckOutcome.partial(alerts,
                        "Cost anomalies could not be checked: " + e.getMessage());
            }
            return CheckOutcome.ok(alerts);
        });
    }

    private String currencySymbol(String currencyCode) {
        if (currencyCode == null) return "$";
        return switch (currencyCode.toUpperCase()) {
            case "USD" -> "$";
            case "INR" -> "₹";
            case "EUR" -> "€";
            case "GBP" -> "£";
            case "JPY" -> "¥";
            case "CAD" -> "CA$";
            case "AUD" -> "A$";
            case "SGD" -> "S$";
            default -> currencyCode + " ";
        };
    }

    /**
     * @param forceRefresh passed through to the security service rather than dropped. It used to
     *     call the no-arg overload, so a user pressing Refresh on the Alerts page rebuilt its
     *     SECURITY rows out of the cached security findings — which is why resolved findings
     *     appeared on both pages at once, and why refreshing either one did not clear them.
     *     <p>
     *     A forced refresh now goes through {@code refreshSecurityFindings}, which additionally
     *     evicts the Redis entry and kicks off a fresh Prowler scan. {@code getSecurityFindings(id,
     *     true)} only bypasses the SCC caches; the Prowler half of the merged list is a pure cache
     *     read, so pressing Refresh could never clear a Prowler-derived finding. Note the Prowler
     *     scan it triggers is asynchronous, so its results land on a subsequent refresh — the
     *     snapshot age check in {@code ProwlerService} is what stops the previous scan's findings
     *     from being served forever in the meantime.
     */
    private CompletableFuture<CheckOutcome> checkSecurityFindings(CloudAccount account, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Security Findings for GCP project {} (forceRefresh={})", gcpProjectId, forceRefresh);
            try {
                List<GcpSecurityFinding> findings = forceRefresh
                        ? gcpSecurityService.refreshSecurityFindings(gcpProjectId).join()
                        : gcpSecurityService.getSecurityFindings(gcpProjectId, false).join();

                List<GcpSecurityFinding> relevant = findings.stream()
                        .filter(f -> f.getSeverity() != null)
                        .filter(f -> "CRITICAL".equalsIgnoreCase(f.getSeverity())
                                || "HIGH".equalsIgnoreCase(f.getSeverity()))
                        .collect(Collectors.toList());

                List<AlertDto> alerts = groupSecurityFindings(relevant);
                log.info("Found {} CRITICAL/HIGH GCP security findings for project {}, grouped into {} alert rows",
                        relevant.size(), gcpProjectId, alerts.size());
                return CheckOutcome.ok(alerts);

            } catch (Exception e) {
                log.error("Error checking GCP security findings for project {}: {}", gcpProjectId, e.getMessage(), e);
                return CheckOutcome.failed("Security findings could not be checked: " + e.getMessage());
            }
        });
    }

    /**
     * Collapse individual findings into one row per category + severity + resource group.
     *
     * <p>
     * A GCP project with continuous integration produced 2,124 CRITICAL container image
     * vulnerability findings, emitted as 2,124 rows whose headline text was the constant string
     * "High Severity Security Finding" and whose only distinguishing detail was a sha256 digest at
     * the end of a 200-character resource URI. Nothing about that list can be read, scanned, sorted
     * or acted upon, and the same list went out by email.
     *
     * <p>
     * The unit people actually reason about is the image repository, not the image digest —
     * forty vulnerable builds of one service is one problem to fix, not forty. Grouping on the
     * resource path with its final version segment removed expresses that, and it takes the same
     * project to roughly a dozen rows.
     *
     * <p>
     * Grouping also stabilises the notification fingerprint as a side effect. The fingerprint is
     * computed over category, name, service, region and status, none of which vary between those
     * 2,124 rows, so it amounted to a row count: pushing one image changed the count and re-sent the
     * entire digest, while swapping five findings for five others changed nothing and sent none.
     * A dozen category-level rows change only when a category genuinely appears or disappears.
     */
    private List<AlertDto> groupSecurityFindings(List<GcpSecurityFinding> findings) {
        // LinkedHashMap so the order findings arrived in is preserved. GcpSecurityService returns
        // SCC results before merged Prowler results, which is a more useful order than hash order.
        Map<String, List<GcpSecurityFinding>> groups = new LinkedHashMap<>();
        for (GcpSecurityFinding finding : findings) {
            groups.computeIfAbsent(groupKey(finding), k -> new ArrayList<>()).add(finding);
        }

        List<AlertDto> alerts = new ArrayList<>(groups.size());
        for (Map.Entry<String, List<GcpSecurityFinding>> entry : groups.entrySet()) {
            List<GcpSecurityFinding> members = entry.getValue();
            GcpSecurityFinding first = members.get(0);

            String category = blankToDefault(first.getCategory(), "SECURITY_FINDING");
            String severity = first.getSeverity().toUpperCase();

            List<String> resources = members.stream()
                    .map(GcpSecurityFinding::getResourceName)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            // The id is derived from the group key rather than a running counter. A counter-based id
            // changes whenever the surrounding list changes, which breaks React keys, breaks
            // per-alert links, and makes an SRE investigation reference a row that no longer exists.
            String uniqueId = "security-" + slug(entry.getKey());

            alerts.add(new AlertDto(
                    uniqueId,
                    category,
                    humanizeCategory(category),
                    describeGroup(first, members.size(), resources),
                    severity,
                    0, // Usage/Limit not applicable
                    0,
                    "SECURITY",
                    // SCC findings are reported without a region on this path, and inventing one
                    // would be worse than saying so. GcpSecurityFinding carries no region field.
                    "global",
                    members.size(),
                    resources));
        }

        // Most-affected first within a severity, so the biggest problem is at the top rather than
        // whichever category SCC happened to return first.
        alerts.sort(Comparator
                .comparingInt((AlertDto a) -> "CRITICAL".equalsIgnoreCase(a.getStatus()) ? 0 : 1)
                .thenComparing(Comparator.comparingInt(AlertDto::getAffectedCount).reversed())
                .thenComparing(AlertDto::getService, Comparator.nullsLast(String::compareTo)));

        return alerts;
    }

    /** category + severity + resource group, which is the identity of one row. */
    private String groupKey(GcpSecurityFinding finding) {
        return blankToDefault(finding.getCategory(), "SECURITY_FINDING").toUpperCase()
                + "|" + finding.getSeverity().toUpperCase()
                + "|" + resourceGroup(finding.getResourceName());
    }

    /**
     * The stable part of a resource name, with the version-specific tail removed.
     *
     * <p>
     * For an Artifact Registry image the digest is what differs between findings that are otherwise
     * the same problem, so {@code .../dockerImages/api-server@sha256:abc…} groups with
     * {@code .../dockerImages/api-server@sha256:def…} under {@code .../dockerImages/api-server}.
     * Tags are treated the same way. Anything without a version marker is returned unchanged, which
     * makes each such resource its own group — correct, since we have no evidence they are related.
     */
    private String resourceGroup(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return "unknown";
        }
        String trimmed = resourceName;
        int digest = trimmed.indexOf('@');
        if (digest > 0) {
            trimmed = trimmed.substring(0, digest);
        } else {
            // A tag separator, but only in the last path segment — a colon earlier in the string is
            // part of the scheme (`//artifactregistry.googleapis.com/...`), not a tag.
            int lastSlash = trimmed.lastIndexOf('/');
            int tag = trimmed.indexOf(':', lastSlash + 1);
            if (tag > 0) {
                trimmed = trimmed.substring(0, tag);
            }
        }
        return trimmed;
    }

    /**
     * The row's description: what the finding is, and how far it spreads.
     *
     * <p>
     * The resource itself is deliberately left out of this text and carried in
     * {@link AlertDto#getResources()} instead. Concatenating a 200-character URI onto the
     * description is what made the old rows unreadable, and it denied the UI any chance to render
     * the resource properly — truncated, copyable, linked.
     */
    private String describeGroup(GcpSecurityFinding first, int count, List<String> resources) {
        String base = first.getDescription() != null && !first.getDescription().isBlank()
                ? first.getDescription().trim()
                : humanizeCategory(blankToDefault(first.getCategory(), "SECURITY_FINDING"));

        if (count <= 1) {
            return base;
        }

        int distinctResources = resources.size();
        String scope = distinctResources == 1
                ? String.format("%d findings on 1 resource", count)
                : String.format("%d findings across %d resources", count, distinctResources);

        String separator = base.endsWith(".") ? " " : ". ";
        return base + separator + scope + ".";
    }

    /** {@code CONTAINER_IMAGE_VULNERABILITY} to {@code Container image vulnerability}. */
    private String humanizeCategory(String category) {
        String cleaned = category.replace('_', ' ').replace('-', ' ').trim();
        if (cleaned.isEmpty()) {
            return "Security finding";
        }
        // Only lower-case a token that looks like SHOUTING. Acronyms such as IAM, SSH, KMS and SQL
        // carry meaning in upper case and "Iam anomalous grant" reads like a typo.
        String[] words = cleaned.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(word.length() <= 3 && word.equals(word.toUpperCase()) ? word : word.toLowerCase());
        }
        String result = sb.toString();
        return Character.toUpperCase(result.charAt(0)) + result.substring(1);
    }

    /** A short, stable, URL-safe id fragment for a group key. */
    private String slug(String groupKey) {
        String normalized = groupKey.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        // Resource paths are long enough to make an unusable DOM id, so the tail is replaced with a
        // hash of the whole key. Hashing the whole key, not just the removed part, keeps it stable.
        if (normalized.length() > 80) {
            return normalized.substring(0, 80) + "-" + Integer.toHexString(groupKey.hashCode());
        }
        return normalized;
    }

    private String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private String determineQuotaStatus(double usage, double limit) {
        if (limit <= 0) return "UNKNOWN"; // Avoid division by zero
        double percentage = (usage / limit) * 100.0;
        if (percentage >= 90) return "CRITICAL";
        if (percentage >= NETWORK_QUOTA_THRESHOLD) return "WARNING"; // Use defined threshold
        return "OK";
    }
}