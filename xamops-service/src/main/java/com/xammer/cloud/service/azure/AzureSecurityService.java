package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.azure.AzureSecurityAlert;
import com.xammer.cloud.dto.azure.AzureSecurityAssessment;
import com.xammer.cloud.dto.azure.AzureSecurityResponse;
import com.xammer.cloud.dto.azure.AzureSecureScoreControl;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for fetching Azure Security data from Microsoft Defender for Cloud.
 *
 * Uses three Microsoft.Security REST APIs:
 *   - Secure Scores:  .../providers/Microsoft.Security/secureScores?api-version=2020-01-01-preview
 *   - Security Alerts: .../providers/Microsoft.Security/alerts?api-version=2021-01-01
 *   - Assessments:    .../providers/Microsoft.Security/assessments?api-version=2021-06-01
 *
 * Requires "Security Reader" role on the subscription.
 * On 403, returns PERMISSION_ERROR status so the frontend can show an actionable message.
 */
@Service
public class AzureSecurityService {

    private static final Logger log = LoggerFactory.getLogger(AzureSecurityService.class);
    private static final String CACHE_PREFIX = "azure:security:";
    private static final String DB_CACHE_PREFIX = "AZURE_SECURITY::";
    private static final int CACHE_TTL_MINUTES = 60;

    /**
     * How old the DB snapshot may be and still be served.
     *
     * <p>
     * Tied to the Redis TTL on purpose. The DB tier is a restart cushion, not a second cache with a
     * longer life — and since {@code cached_data} has no expiry column, serving this row while also
     * re-warming Redis from it meant a stale posture snapshot renewed its own lifetime on every
     * read. A customer who had remediated their Defender findings would keep seeing them, which is
     * the same bug reported on the GCP pages.
     */
    private static final int SNAPSHOT_MAX_AGE_MINUTES = CACHE_TTL_MINUTES;
    private static final String BASE_URL = "https://management.azure.com/subscriptions/";
    private static final String SCOPE = "https://management.azure.com/.default";

    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final AzureArmClient armClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureSecurityService(AzureClientProvider clientProvider,
                                RedisCacheService redisCache,
                                CachedDataRepository cachedDataRepository,
                                AzureArmClient armClient) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.armClient = armClient;
    }

    /**
     * Returns Azure security data (score, alerts, assessments) for the given subscription.
     * Cache strategy: Redis (60-min TTL) → DB snapshot → Azure API.
     * Pass forceRefresh=true to bypass all caches.
     */
    public AzureSecurityResponse getSecurityData(String subscriptionId, boolean forceRefresh) {
        String cacheKey = CACHE_PREFIX + subscriptionId;
        String dbKey = DB_CACHE_PREFIX + subscriptionId;

        if (!forceRefresh) {
            // 1. Try Redis
            Optional<AzureSecurityResponse> cached = redisCache.get(cacheKey, AzureSecurityResponse.class);
            if (cached.isPresent()) {
                log.info("Returning Redis-cached security response for {}", subscriptionId);
                try {
                    return objectMapper.convertValue(cached.get(), AzureSecurityResponse.class);
                } catch (Exception e) {
                    log.warn("Redis deserialization failed, trying DB: {}", e.getMessage());
                }
            }

            // 2. Try DB snapshot, but only while it is still current enough to serve.
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    LocalDateTime stamped = dbSnapshot.get().getLastUpdated();
                    // An unstamped row is of unknown age, which on a security page has to mean
                    // "go and look" rather than "assume it is current".
                    boolean tooOld = stamped == null
                            || stamped.isBefore(LocalDateTime.now().minusMinutes(SNAPSHOT_MAX_AGE_MINUTES));
                    if (tooOld) {
                        log.info("Azure security DB snapshot for {} is older than {} min — ignoring it and "
                                + "querying Defender", subscriptionId, SNAPSHOT_MAX_AGE_MINUTES);
                    } else {
                        AzureSecurityResponse response = objectMapper.readValue(
                                dbSnapshot.get().getJsonData(), AzureSecurityResponse.class);
                        log.info("Returning DB-cached security response for {} — re-warming Redis", subscriptionId);
                        redisCache.put(cacheKey, response, CACHE_TTL_MINUTES);
                        return response;
                    }
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for security {}: {}", subscriptionId, e.getMessage());
            }
        }

        AzureSecurityResponse response = fetchFromDefender(subscriptionId);

        // Cache only a response Defender actually answered. PERMISSION_ERROR and API_ERROR are
        // failures carrying a zero score and empty lists; storing them would replace a good
        // snapshot with an outage and report the subscription as having nothing wrong with it.
        // A genuine NO_DATA result — reachable, nothing to report — is stored, because that is
        // the honest answer for a clean subscription and the one a remediating customer needs.
        boolean answered = response.getStatus() != AzureSecurityResponse.Status.PERMISSION_ERROR
                && response.getStatus() != AzureSecurityResponse.Status.API_ERROR;
        if (answered) {
            redisCache.put(cacheKey, response, CACHE_TTL_MINUTES);
            saveToDb(dbKey, response);
        } else {
            log.warn("Azure security fetch for {} returned {} — not caching, keeping any previous snapshot",
                    subscriptionId, response.getStatus());
        }
        return response;
    }

    private void saveToDb(String dbKey, AzureSecurityResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            // Same clock the staleness check compares against; Asia/Kolkata made every snapshot
            // look 5.5h younger than it was on a UTC host.
            entity.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(entity);
            log.info("✅ DB snapshot saved for Azure security: {}", dbKey);
        } catch (Exception e) {
            log.warn("Failed to save DB snapshot for Azure security {}: {}", dbKey, e.getMessage());
        }
    }

    private AzureSecurityResponse fetchFromDefender(String subscriptionId) {
        try {
            TokenCredential credential = clientProvider.getCredential(subscriptionId);
            TokenRequestContext tokenCtx = new TokenRequestContext().addScopes(SCOPE);
            String bearerToken = credential.getToken(tokenCtx).block().getToken();

            SecureScore score = fetchSecureScore(subscriptionId, bearerToken);
            double secureScore = score.current();
            List<AzureSecurityAlert> alerts = fetchAlerts(subscriptionId, bearerToken);
            List<AzureSecurityAssessment> assessments = fetchAssessments(subscriptionId, bearerToken);
            List<AzureSecureScoreControl> scoreControls = fetchSecureScoreControls(subscriptionId, bearerToken);

            boolean hasData = secureScore > 0 || !alerts.isEmpty() || !assessments.isEmpty();
            AzureSecurityResponse.Status status = hasData
                    ? AzureSecurityResponse.Status.OK
                    : AzureSecurityResponse.Status.NO_DATA;

            log.info("Azure Security: score={}, alerts={}, assessments(unhealthy)={}, controls={} for {}",
                    secureScore, alerts.size(), assessments.size(), scoreControls.size(), subscriptionId);

            // Prefer Defender's own maximum. If the score endpoint did not report one, derive it
            // from the per-control breakdown rather than falling back to a misleading 100.
            int maxScore = score.max();
            if (maxScore <= 0) {
                maxScore = (int) Math.round(scoreControls.stream()
                        .mapToDouble(AzureSecureScoreControl::getMaxScore)
                        .sum());
            }
            if (maxScore <= 0) {
                maxScore = 100;
                log.warn("Azure Security: no secure-score maximum available for {} — falling back to 100, "
                        + "so the displayed percentage may understate posture", subscriptionId);
            }

            return new AzureSecurityResponse(status, secureScore, maxScore, alerts, assessments, scoreControls);

        } catch (PermissionException e) {
            log.warn("SP lacks Security Reader role for subscription {}. " +
                    "Assign 'Security Reader' role to enable security data.", subscriptionId);
            return new AzureSecurityResponse(AzureSecurityResponse.Status.PERMISSION_ERROR,
                    0, 100, new ArrayList<>(), new ArrayList<>());
        } catch (Exception e) {
            log.error("Failed to fetch Azure security data for {}: {}", subscriptionId, e.getMessage(), e);
            return new AzureSecurityResponse(AzureSecurityResponse.Status.API_ERROR,
                    0, 100, new ArrayList<>(), new ArrayList<>());
        }
    }

    // ── Secure Score ──────────────────────────────────────────────────────────

    /**
     * The subscription's secure score and the maximum it is measured against.
     *
     * @param current points earned
     * @param max     points available; Defender scales this to the controls in scope, so it is
     *                rarely 100
     */
    private record SecureScore(double current, int max) {
        static final SecureScore UNKNOWN = new SecureScore(0, 0);
    }

    /**
     * Reads the subscription secure score, including its real denominator.
     *
     * <p>The denominator used to be hardcoded to 100, so a subscription scoring 8 of 13
     * available points was displayed as "8.0 Out of 100 (8%)" instead of 61%. Defender scales
     * the maximum to the controls actually in scope, so 100 is almost never right.
     */
    private SecureScore fetchSecureScore(String subscriptionId, String bearerToken) throws Exception {
        String uri = BASE_URL + subscriptionId
                + "/providers/Microsoft.Security/secureScores"
                + "?api-version=2020-01-01-preview";

        String body = doGet(uri, bearerToken, subscriptionId, "secureScores");
        if (body == null) return SecureScore.UNKNOWN;

        JsonNode root = objectMapper.readTree(body);
        JsonNode value = root.path("value");
        if (!value.isArray() || value.isEmpty()) return SecureScore.UNKNOWN;

        // Take the first score entry (typically "ascScore" — the overall subscription score)
        JsonNode scoreNode = value.get(0).path("properties").path("score");
        double current = scoreNode.path("current").asDouble(0);
        int max = scoreNode.path("max").asInt(0);
        return new SecureScore(current, max);
    }

    // ── Secure Score Controls (per-control breakdown) ──────────────────────────

    /**
     * Fetches the per-control secure score breakdown so users can see which
     * controls drag down their score and how many points are recoverable.
     * Sorted by recoverable score (biggest opportunity first).
     */
    private List<AzureSecureScoreControl> fetchSecureScoreControls(String subscriptionId,
                                                                   String bearerToken) throws Exception {
        String uri = BASE_URL + subscriptionId
                + "/providers/Microsoft.Security/secureScoreControls"
                + "?api-version=2020-01-01-preview";

        String body = doGet(uri, bearerToken, subscriptionId, "secureScoreControls");
        if (body == null) return new ArrayList<>();

        List<AzureSecureScoreControl> result = new ArrayList<>();
        JsonNode root = objectMapper.readTree(body);
        JsonNode value = root.path("value");
        if (!value.isArray()) return result;

        for (JsonNode node : value) {
            try {
                JsonNode props = node.path("properties");
                String name = props.path("displayName").asText(node.path("name").asText(""));

                JsonNode score = props.path("score");
                double current = score.path("current").asDouble(0);
                double max = score.path("max").asDouble(0);
                double percentage = score.path("percentage").asDouble(0) * 100; // API gives 0-1

                int healthy = props.path("healthyResourceCount").asInt(0);
                int unhealthy = props.path("unhealthyResourceCount").asInt(0);

                // Skip controls with no scoring weight (max 0) — they add no signal
                if (max <= 0) continue;

                result.add(new AzureSecureScoreControl(name, current, max,
                        Math.round(percentage * 10) / 10.0, healthy, unhealthy));
            } catch (Exception e) {
                log.warn("Failed to parse secure score control item: {}", e.getMessage());
            }
        }

        // Biggest recoverable opportunity first
        result.sort((a, b) -> Double.compare(b.getRecoverableScore(), a.getRecoverableScore()));
        return result;
    }

    // ── Security Alerts ───────────────────────────────────────────────────────

    private List<AzureSecurityAlert> fetchAlerts(String subscriptionId, String bearerToken) throws Exception {
        String uri = BASE_URL + subscriptionId
                + "/providers/Microsoft.Security/alerts"
                + "?api-version=2021-01-01";

        String body = doGet(uri, bearerToken, subscriptionId, "alerts");
        if (body == null) return new ArrayList<>();

        return parseAlerts(body);
    }

    private List<AzureSecurityAlert> parseAlerts(String json) throws Exception {
        List<AzureSecurityAlert> result = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode value = root.path("value");
        if (!value.isArray()) return result;

        for (JsonNode node : value) {
            try {
                JsonNode props = node.path("properties");
                AzureSecurityAlert alert = new AzureSecurityAlert();
                alert.setAlertId(node.path("name").asText(""));
                alert.setAlertName(props.path("alertDisplayName").asText(
                        props.path("alertType").asText("")));
                alert.setSeverity(props.path("severity").asText("Medium"));
                alert.setStatus(props.path("status").asText("Active"));
                alert.setDescription(props.path("description").asText(""));
                alert.setTimeGenerated(props.path("timeGeneratedUtc").asText(
                        props.path("startTimeUtc").asText("")));

                // Extract resource name from resourceIdentifiers array
                JsonNode identifiers = props.path("resourceIdentifiers");
                String resourceId = "";
                if (identifiers.isArray() && !identifiers.isEmpty()) {
                    resourceId = identifiers.get(0).path("azureResourceId").asText(
                            identifiers.get(0).path("workspaceId").asText(""));
                }
                alert.setResourceName(resourceId.isEmpty() ? "—"
                        : resourceId.substring(resourceId.lastIndexOf('/') + 1));

                result.add(alert);
            } catch (Exception e) {
                log.warn("Failed to parse security alert item: {}", e.getMessage());
            }
        }

        return result;
    }

    // ── Security Assessments ──────────────────────────────────────────────────

    private List<AzureSecurityAssessment> fetchAssessments(String subscriptionId,
                                                            String bearerToken) throws Exception {
        String uri = BASE_URL + subscriptionId
                + "/providers/Microsoft.Security/assessments"
                + "?api-version=2021-06-01";

        String body = doGet(uri, bearerToken, subscriptionId, "assessments");
        if (body == null) return new ArrayList<>();

        return parseAssessments(body);
    }

    private List<AzureSecurityAssessment> parseAssessments(String json) throws Exception {
        List<AzureSecurityAssessment> result = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode value = root.path("value");
        if (!value.isArray()) return result;

        for (JsonNode node : value) {
            try {
                JsonNode props = node.path("properties");
                String statusCode = props.path("status").path("code").asText("NotApplicable");

                // Only include Unhealthy assessments (actionable findings)
                if (!"Unhealthy".equalsIgnoreCase(statusCode)) continue;

                AzureSecurityAssessment assessment = new AzureSecurityAssessment();
                assessment.setAssessmentId(node.path("name").asText(""));
                assessment.setDisplayName(props.path("displayName").asText(""));
                assessment.setStatusCode(statusCode);

                JsonNode metadata = props.path("metadata");
                assessment.setSeverity(metadata.path("severity").asText("Medium"));
                assessment.setRemediation(metadata.path("remediationDescription").asText(""));

                // Category from metadata.categories array (take first element)
                JsonNode categories = metadata.path("categories");
                String category = (categories.isArray() && !categories.isEmpty())
                        ? categories.get(0).asText("Other")
                        : "Other";
                assessment.setCategory(category);

                // Resource: prefer resourceDetails.id; fall back to stripping the security suffix
                // from the assessment's own top-level id (always present and reliable)
                String resourceId = props.path("resourceDetails").path("id").asText("");

                if (resourceId.isEmpty()) {
                    String fullId = node.path("id").asText("");
                    int secIdx = fullId.toLowerCase().indexOf("/providers/microsoft.security/assessments/");
                    if (secIdx > 0) {
                        resourceId = fullId.substring(0, secIdx);
                    }
                }

                assessment.setResourceId(resourceId);

                if (resourceId.isEmpty()) {
                    assessment.setResourceName("—");
                } else {
                    String lastName = resourceId.substring(resourceId.lastIndexOf('/') + 1);
                    boolean isGuid = lastName.matches(
                            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
                    assessment.setResourceName(isGuid ? "Subscription" : lastName);
                }

                assessment.setComplianceControl(mapToSoc2Control(category, assessment.getDisplayName()));
                result.add(assessment);
            } catch (Exception e) {
                log.warn("Failed to parse security assessment item: {}", e.getMessage());
            }
        }

        return result;
    }

    /**
     * Maps a Defender for Cloud assessment to its SOC2 Trust Service Criteria control.
     * Uses the Defender category first, then falls back to keyword matching on the
     * recommendation display name. Returns null when no SOC2 mapping applies.
     *
     * CC6.1  – Logical access (IAM, MFA, identity, role assignment)
     * CC6.6  – Network security (firewall, NSG, public exposure)
     * CC6.7  – Data protection (encryption at rest/in transit, TLS, HTTPS)
     * CC6.8  – Key / secret management (Key Vault, rotation)
     * CC7.1  – Monitoring and audit logging (activity logs, diagnostics)
     */
    private String mapToSoc2Control(String category, String displayName) {
        // --- category-level mapping (Defender categories are stable) ---
        if (category != null) {
            switch (category) {
                case "IdentityAndAccess": return "SOC2 CC6.1";
                case "Networking":        return "SOC2 CC6.6";
                case "KeyVault":          return "SOC2 CC6.8";
                // Data / Compute / AppServices: fall through to keyword matching
                default: break;
            }
        }

        // --- keyword matching on recommendation display name ---
        if (displayName == null || displayName.isBlank()) return null;
        String lower = displayName.toLowerCase();

        if (lower.contains("mfa") || lower.contains("multi-factor") || lower.contains("authentication")
                || lower.contains("privileged") || lower.contains("role assignment")
                || lower.contains("identity") || lower.contains("least privilege")) {
            return "SOC2 CC6.1";
        }
        if (lower.contains("firewall") || lower.contains("nsg") || lower.contains("public ip")
                || lower.contains("internet-facing") || lower.contains("open port")
                || lower.contains("ssh") || lower.contains("rdp") || lower.contains("network access")) {
            return "SOC2 CC6.6";
        }
        if (lower.contains("encrypt") || lower.contains("tls") || lower.contains("https")
                || lower.contains("ssl") || lower.contains("secure transfer")
                || lower.contains("disk encryption") || lower.contains("at rest")) {
            return "SOC2 CC6.7";
        }
        if (lower.contains("key vault") || lower.contains("secret") || lower.contains("rotation")
                || lower.contains("certificate expir") || lower.contains("key expir")) {
            return "SOC2 CC6.8";
        }
        if (lower.contains("audit log") || lower.contains("diagnostic") || lower.contains("monitor")
                || lower.contains("activity log") || lower.contains("log analytic")) {
            return "SOC2 CC7.1";
        }

        return null;
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    /**
     * Executes a GET through the shared {@link AzureArmClient} and returns the response body,
     * or null when there is no data to read.
     *
     * <p>Behaviour is preserved from the previous per-call implementation: a 403 raises
     * {@link PermissionException} (so {@code fetchFromDefender} still reports PERMISSION_ERROR),
     * a 404 yields null (the Defender feature may not be enabled), and every other non-success —
     * a 401, a throttle, a server error, or a transport failure — is folded to null rather than
     * failing the whole security fetch. {@code armClient.get} returns an empty Optional on 404
     * and throws {@link AzureArmClient.AzureArmException} on an authorization/throttle/server
     * failure, which this maps onto those two outcomes.
     */
    private String doGet(String uri, String bearerToken,
                         String subscriptionId, String resourceType) throws Exception {
        try {
            Optional<String> body = armClient.get(uri, bearerToken);
            if (body.isEmpty()) {
                // 404 from ARM — feature not enabled for this subscription.
                log.warn("Azure Security {} not found for {} (feature may not be enabled)",
                        resourceType, subscriptionId);
                return null;
            }
            return body.get();
        } catch (AzureArmClient.AzureArmException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("HTTP 403") || message.contains("denied access")) {
                throw new PermissionException("403 fetching " + resourceType + " for " + subscriptionId);
            }
            // 401, throttle, server or transport failure — treated as "no data" as before.
            log.warn("Azure Security {} unavailable for {}: {}", resourceType, subscriptionId, message);
            return null;
        }
    }

    // ── Inner exception ───────────────────────────────────────────────────────

    private static class PermissionException extends RuntimeException {
        PermissionException(String message) { super(message); }
    }
}
