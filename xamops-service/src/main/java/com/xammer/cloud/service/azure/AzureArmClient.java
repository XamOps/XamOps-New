package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.xammer.cloud.permissions.AzureDenialClassifier;
import com.xammer.cloud.permissions.PermissionGap;
import com.xammer.cloud.permissions.PermissionGapRegistry;
import com.xammer.cloud.permissions.PermissionGaps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Azure Resource Manager REST client for the optimization services.
 *
 * <p>Consolidates behaviour that was duplicated and partly broken across
 * {@code AzureWasteService} and {@code AzureRightsizingService}:
 *
 * <ul>
 *   <li>A single reusable {@link HttpClient}. Both services constructed a new one per
 *       request at four call sites, discarding connection pooling entirely.</li>
 *   <li>Connect and request timeouts. Neither service set any, so a hung ARM endpoint
 *       could pin a request thread indefinitely.</li>
 *   <li>Cached bearer tokens. Tokens were re-fetched three or more times per scan and the
 *       underlying {@code ClientSecretCredential} was rebuilt on every call, discarding the
 *       SDK's own token cache.</li>
 *   <li>Error visibility. Non-2xx responses were collapsed to {@code null} at
 *       {@code log.debug}, so a subscription-wide 403 was invisible at default log level
 *       and rendered as "nothing to optimize".</li>
 *   <li>Full pagination. {@code armList} stopped at 20 pages and silently truncated.</li>
 * </ul>
 */
@Service
public class AzureArmClient {

    private static final Logger logger = LoggerFactory.getLogger(AzureArmClient.class);

    public static final String ARM_BASE = "https://management.azure.com";
    private static final String ARM_SCOPE = "https://management.azure.com/.default";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Refresh a token this long before its stated expiry. */
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);

    /** Hard ceiling on pages followed, to bound memory on very large subscriptions. */
    private static final int MAX_PAGES = 1000;

    /**
     * Attempts per call, including the first.
     *
     * <p>
     * Four, from measurement rather than taste. Against a live subscription, four back-to-back
     * {@code Microsoft.CostManagement/query} calls exhausted the client-type quota
     * ({@code clienttype-requests: DefaultQuota:0}), and full recovery took <b>53 seconds</b>. Azure's
     * retry-after header counted that window down honestly across polls — 41s, then 24s, then 7s,
     * then success.
     *
     * <p>
     * Three attempts was tried first and came up just short: the first 429 of that run carried no
     * header, so the fallback wait applied, and the three sleeps summed to 47s against a 53s
     * recovery. Losing the data by a six-second margin is the worst possible outcome, hence the
     * extra attempt and the longer fallback below.
     */
    private static final int MAX_ATTEMPTS = 4;

    /**
     * Longest we will honour a retry-after for.
     *
     * <p>
     * Azure has asked for 45s in observed traffic, so the cap has to be above that to be useful
     * at all. Beyond a minute, waiting is worse than failing: the caller keeps its previous
     * cached figures either way, and a scheduled pass holding a thread for minutes per
     * subscription delays every job behind it.
     *
     * <p>
     * Note the interaction with {@code xamops.azure.dashboard.section-timeout-seconds} (120s). In
     * the measured case the retries cost ~50s and finish comfortably inside it; three
     * maximum-length waits would exceed it and the section deadline cuts the retrying short. That
     * precedence is intended — the deadline is the outer bound, and a section that cannot answer
     * within it is reported as missing rather than left running.
     */
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(60);

    /**
     * Fallback wait when a throttle response carries no retry-after at all.
     *
     * <p>
     * 20s, not the 2s an exponential ladder would start at. Cost Management's observed recovery
     * from an exhausted quota is ~53s, so a short first sleep just spends an attempt to learn
     * nothing. Three fallback sleeps at this length cover that window; the first 429 of a burst is
     * also the one most likely to omit the header.
     */
    private static final Duration DEFAULT_RETRY_WAIT = Duration.ofSeconds(20);

    /**
     * Headers Azure uses to say how long to wait, in preference order.
     *
     * <p>
     * The standard {@code Retry-After} is listed first but Cost Management does not send it.
     * What it actually returns is the vendor-specific header below — confirmed by capturing a
     * live 429 from {@code Microsoft.CostManagement/query}, whose response carried
     * {@code x-ms-ratelimit-microsoft.costmanagement-clienttype-retry-after: 45} and no
     * {@code Retry-After}. A retry built only on exponential backoff gets this wrong: a fixed
     * 12/24/36s ladder was tried against the same endpoint and was refused four times in a row,
     * because the backoff was unrelated to the window Azure was actually enforcing. Reading the
     * header is the difference between a retry that works and one that just makes more 429s.
     */
    private static final List<String> RETRY_AFTER_HEADERS = List.of(
            "retry-after",
            "x-ms-ratelimit-microsoft.costmanagement-clienttype-retry-after",
            "x-ms-ratelimit-microsoft.costmanagement-entity-retry-after",
            "x-ms-ratelimit-microsoft.costmanagement-tenant-retry-after");

    private final AzureClientProvider clientProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    private final PermissionGapRegistry permissionGapRegistry;

    public AzureArmClient(AzureClientProvider clientProvider, PermissionGapRegistry permissionGapRegistry) {
        this.clientProvider = clientProvider;
        this.permissionGapRegistry = permissionGapRegistry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── Permission-gap tracking ──────────────────────────────────────────────
    //
    // Azure has no interceptor seam like the AWS SDK's ExecutionInterceptor, so denials are
    // recorded from the two places that see them: getToken for authentication failures, and the
    // 401/403 branch of send for authorisation failures. Every read path in this class funnels
    // through send, so those two cover the whole Azure integration.
    //
    // All four helpers swallow their own exceptions. Permission tracking is diagnostic; it must
    // never convert a failed Azure call into a different failure.

    /** The service principal could not authenticate at all. */
    private void recordUnreachable(String subscriptionId, Throwable failure) {
        try {
            AzureDenialClassifier.Result denial = AzureDenialClassifier.classify(failure);
            // Recorded even when the classifier does not recognise the cause: reaching this point
            // means the token call failed outright, and without a token nothing about this
            // subscription can be read. The classifier only refines the error code.
            String code = denial != null ? denial.getErrorCode() : "AuthenticationFailed";
            String message = denial != null ? denial.getMessage() : failure.getMessage();
            permissionGapRegistry.record(PermissionGaps.azureUnreachable(subscriptionId, code, message));
        } catch (Exception e) {
            logger.debug("Could not record Azure permission gap for {}: {}", subscriptionId, e.getMessage());
        }
    }

    /** A 401 or 403 on a specific ARM request. */
    private void recordHttpDenial(String url, int status) {
        try {
            String subscriptionId = subscriptionIdFrom(url);
            if (subscriptionId == null) {
                return;
            }
            String action = actionFrom(url);
            if (status == 401) {
                permissionGapRegistry.record(PermissionGaps.azureUnreachable(
                        subscriptionId, "Unauthorized", "Azure rejected the ARM token for " + action));
            } else {
                permissionGapRegistry.record(PermissionGaps.azureDenied(
                        subscriptionId, action, "AuthorizationFailed",
                        "The service principal is not authorised to call " + action,
                        PermissionGap.Kind.PERMISSION));
            }
        } catch (Exception e) {
            logger.debug("Could not record Azure permission gap for {}: {}", url, e.getMessage());
        }
    }

    private void resolveGapForUrl(String url) {
        String subscriptionId = subscriptionIdFrom(url);
        if (subscriptionId != null) {
            resolveGap(subscriptionId, actionFrom(url));
        }
    }

    private void resolveGap(String subscriptionId, String action) {
        try {
            permissionGapRegistry.resolveIfKnown(subscriptionId, action);
        } catch (Exception e) {
            logger.debug("Could not resolve Azure permission gap for {}: {}", subscriptionId, e.getMessage());
        }
    }

    /**
     * Pulls the subscription id out of an ARM URL.
     *
     * <p>{@code send} is handed only a URL and a bearer token, not the subscription it belongs
     * to. Rather than thread an extra parameter through four public methods and every caller,
     * it is recovered from the path — every ARM URL this client builds contains
     * {@code /subscriptions/<id>/}.
     */
    static String subscriptionIdFrom(String url) {
        if (url == null) {
            return null;
        }
        int start = url.indexOf("/subscriptions/");
        if (start < 0) {
            return null;
        }
        start += "/subscriptions/".length();
        int end = start;
        while (end < url.length() && url.charAt(end) != '/' && url.charAt(end) != '?') {
            end++;
        }
        return end > start ? url.substring(start, end) : null;
    }

    /**
     * The RBAC action an ARM URL represents, as far as the path reveals it.
     *
     * <p>Good enough for the catalog lookup, which only needs the resource provider — the part
     * after {@code /providers/}. Falls back to a subscription read, which is what a URL with no
     * provider segment is in practice.
     */
    static String actionFrom(String url) {
        String fallback = "Microsoft.Resources/subscriptions/read";
        if (url == null) {
            return fallback;
        }
        int start = url.indexOf("/providers/");
        if (start < 0) {
            return fallback;
        }
        start += "/providers/".length();
        int end = start;
        int slashes = 0;
        // Provider plus one resource-type segment, e.g. Microsoft.Compute/virtualMachines.
        while (end < url.length() && url.charAt(end) != '?') {
            if (url.charAt(end) == '/' && ++slashes == 2) {
                break;
            }
            end++;
        }
        String path = url.substring(start, end);
        return path.isBlank() ? fallback : path + "/read";
    }

    /**
     * Returns a cached ARM bearer token for a subscription, fetching one if needed.
     *
     * @throws AzureArmException when a token cannot be obtained
     */
    public String getToken(String subscriptionId) {
        CachedToken cached = tokenCache.get(subscriptionId);
        if (cached != null && cached.isUsable()) {
            return cached.token();
        }
        try {
            TokenCredential credential = clientProvider.getCredential(subscriptionId);
            var accessToken = credential.getToken(new TokenRequestContext().addScopes(ARM_SCOPE)).block();
            if (accessToken == null) {
                throw new AzureArmException("Azure returned no access token for subscription " + subscriptionId);
            }
            CachedToken fresh = new CachedToken(accessToken.getToken(), accessToken.getExpiresAt());
            tokenCache.put(subscriptionId, fresh);
            // Authentication works, so retract any gap a previous run recorded.
            resolveGap(subscriptionId, "Microsoft.Resources/subscriptions/read");
            return fresh.token();
        } catch (AzureArmException e) {
            throw e;
        } catch (Exception e) {
            // The service principal itself could not authenticate — an expired client secret,
            // a wrong tenant, or a removed app registration. Distinct from a 403 below: nothing
            // at all loads for this subscription, so it is recorded as unreachable.
            recordUnreachable(subscriptionId, e);
            throw new AzureArmException(
                    "Failed to obtain ARM token for subscription " + subscriptionId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Authenticated GET against ARM.
     *
     * @return the response body, or empty when the resource is absent (404)
     * @throws AzureArmException on an authorization, throttling or server failure, so the
     *                           caller can distinguish these from "no data"
     */
    public Optional<String> get(String url, String token) {
        return send(url, token, null);
    }

    /**
     * Authenticated POST against ARM, for the endpoints that require one.
     *
     * <p>
     * Cost Management's {@code query} endpoint is a POST — the filter, grouping and time period go
     * in the body — so it cannot use {@link #get}. Before this existed, {@code AzureCostQueryService}
     * built its own {@code HttpClient.newHttpClient()} at two call sites with no connect timeout, no
     * request timeout, no retry and no reuse. That is the single most throttle-prone endpoint in the
     * Azure integration, so it was also the one least able to afford it.
     *
     * @return the response body, or empty when the resource is absent (404)
     * @throws AzureArmException on an authorization, throttling or server failure
     */
    public Optional<String> post(String url, String token, String jsonBody) {
        return send(url, token, jsonBody);
    }

    /**
     * Issues one ARM request, retrying throttles and transient server errors.
     *
     * <p>
     * A null body means GET. Retries are deliberately narrow: 429 and 5xx only. A 403 is a role
     * assignment that will still be missing in ten seconds, and a 404 is an answer — retrying
     * either would turn a clear failure into a slow one.
     */
    private Optional<String> send(String url, String token, String jsonBody) {
        AzureArmException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/json");

                if (jsonBody == null) {
                    builder.GET();
                } else {
                    builder.header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
                }

                HttpResponse<String> response = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                String method = jsonBody == null ? "GET" : "POST";

                if (status >= 200 && status < 300) {
                    if (attempt > 1) {
                        logger.info("ARM {} {} succeeded on attempt {}", method, url, attempt);
                    }
                    resolveGapForUrl(url);
                    return Optional.of(response.body());
                }
                if (status == 404) {
                    logger.debug("ARM {} {} returned 404", method, url);
                    return Optional.empty();
                }
                // Actionable configuration problems, not absent data — and not worth retrying.
                if (status == 401 || status == 403) {
                    // Recorded before throwing, so the banner can name the missing action rather
                    // than the customer seeing only a degraded page. The ARM URL carries both the
                    // subscription id and the resource provider, which is everything the gap needs.
                    recordHttpDenial(url, status);
                    throw new AzureArmException(String.format(
                            "Azure denied access (HTTP %d). Check the service principal's role assignments. URL: %s",
                            status, url));
                }

                if (status == 429 || status >= 500) {
                    lastFailure = new AzureArmException(status == 429
                            ? "Azure throttled the request (HTTP 429). URL: " + url
                            : String.format("Azure returned HTTP %d for %s", status, url));

                    if (attempt == MAX_ATTEMPTS) {
                        break;
                    }
                    Duration wait = retryWait(response, attempt);
                    logger.warn("ARM {} {} returned HTTP {} (attempt {}/{}) — retrying in {}s",
                            method, url, status, attempt, MAX_ATTEMPTS, wait.toSeconds());
                    if (!sleep(wait)) {
                        break;
                    }
                    continue;
                }

                // Azure's own reason, which was being discarded. A 400 from Cost Management says
                // exactly which part of a query definition it would not accept — "the dimension X is
                // not supported for this scope", "invalid query definition" — and that sentence is
                // the whole diagnosis. Without it, a section that reports itself unavailable is
                // indistinguishable from one whose subscription genuinely has no data, and the only
                // way to tell them apart was to reproduce the call by hand.
                //
                // Truncated because an error body is occasionally a stack of nested details, and
                // logged as a body rather than parsed: the shape varies by status and the text is
                // for a human, not for a branch.
                logger.warn("ARM {} {} returned unexpected HTTP {} — {}", method, url, status,
                        summarise(response.body()));
                return Optional.empty();

            } catch (AzureArmException e) {
                throw e;
            } catch (java.io.IOException e) {
                // Includes HttpTimeoutException. Worth one more try: a dropped connection or a
                // single slow response is exactly the transient case retrying exists for.
                lastFailure = new AzureArmException(
                        "ARM call failed for " + url + ": " + e.getMessage(), e);
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                Duration wait = backoff(attempt);
                logger.warn("ARM call to {} failed ({}) on attempt {}/{} — retrying in {}s",
                        url, e.getMessage(), attempt, MAX_ATTEMPTS, wait.toSeconds());
                if (!sleep(wait)) {
                    break;
                }
            } catch (Exception e) {
                throw new AzureArmException("ARM call failed for " + url + ": " + e.getMessage(), e);
            }
        }

        throw lastFailure != null ? lastFailure
                : new AzureArmException("ARM call failed for " + url + " after " + MAX_ATTEMPTS + " attempts");
    }

    /** How much of an error body is worth a log line. Long enough for the message, not the stack. */
    private static final int ERROR_BODY_LIMIT = 600;

    /**
     * An error response body, trimmed to one log line.
     *
     * <p>Only reached on a non-2xx, where the body is an error document rather than data — so there is
     * nothing here a customer would recognise as theirs. Newlines are collapsed so the line stays one
     * line in an aggregator.
     */
    private static String summarise(String body) {
        if (body == null || body.isBlank()) {
            return "no response body";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= ERROR_BODY_LIMIT ? flat
                : flat.substring(0, ERROR_BODY_LIMIT) + "… (truncated)";
    }

    /**
     * How long to wait before retrying, preferring what Azure asked for.
     *
     * <p>
     * Azure knows its own throttle window and says so in a header; guessing is strictly worse.
     * Exponential backoff is only the fallback for a response that carries no hint.
     */
    private Duration retryWait(HttpResponse<String> response, int attempt) {
        for (String header : RETRY_AFTER_HEADERS) {
            Optional<String> value = response.headers().firstValue(header);
            if (value.isEmpty()) {
                continue;
            }
            try {
                long seconds = Long.parseLong(value.get().trim());
                if (seconds > 0) {
                    // Capped rather than trusted outright: the header is advice, and an
                    // unbounded wait would let one subscription hold a scheduler thread.
                    return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_WAIT.toSeconds()));
                }
            } catch (NumberFormatException e) {
                // Retry-After may also be an HTTP date. Not worth parsing for a fallback path.
                logger.debug("Could not parse {} value '{}'", header, value.get());
            }
        }
        Duration fallback = backoff(attempt);
        return fallback.compareTo(DEFAULT_RETRY_WAIT) < 0 ? DEFAULT_RETRY_WAIT : fallback;
    }

    /** 2s, 4s, 8s … capped. Used when Azure gives no retry-after. */
    private Duration backoff(int attempt) {
        long seconds = Math.min((long) Math.pow(2, attempt), MAX_RETRY_WAIT.toSeconds());
        return Duration.ofSeconds(seconds);
    }

    /** @return false when the wait was interrupted, so the caller stops retrying. */
    private boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            // Restore the flag and give up rather than swallowing it: on shutdown the pool is
            // trying to stop us, and a retry loop that ignores that delays termination.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Same as {@link #get} but converts every failure into an empty result.
     *
     * <p>For per-resource metric lookups where one failure should not abort a whole scan.
     * Logs at warn so a systematic failure is visible at default log level.
     */
    public Optional<String> getQuietly(String url, String token) {
        try {
            return get(url, token);
        } catch (AzureArmException e) {
            logger.warn("ARM GET suppressed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Lists every entry in an ARM {@code value} array, following {@code nextLink} to the end.
     *
     * @throws AzureArmException if the first page fails, so a permissions problem is not
     *                           reported as an empty resource list
     */
    public List<JsonNode> list(String url, String token) {
        List<JsonNode> out = new ArrayList<>();
        String next = url;
        int pages = 0;

        while (next != null && !next.isBlank() && pages < MAX_PAGES) {
            Optional<String> body = pages == 0 ? get(next, token) : getQuietly(next, token);
            if (body.isEmpty()) {
                break;
            }
            try {
                JsonNode root = objectMapper.readTree(body.get());
                JsonNode value = root.path("value");
                if (value.isArray()) {
                    value.forEach(out::add);
                }
                next = root.path("nextLink").asText(null);
            } catch (Exception e) {
                logger.warn("Could not parse ARM response for {}: {}", url, e.getMessage());
                break;
            }
            pages++;
        }
        if (pages >= MAX_PAGES) {
            logger.warn("ARM list for {} hit the {}-page ceiling; results may be truncated", url, MAX_PAGES);
        }
        return out;
    }

    /** Signals an ARM call that failed for a reason other than absent data. */
    public static class AzureArmException extends RuntimeException {
        public AzureArmException(String message) {
            super(message);
        }

        public AzureArmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A bearer token with its expiry, so it can be reused until shortly before it lapses. */
    private record CachedToken(String token, OffsetDateTime expiresAt) {
        boolean isUsable() {
            return token != null && !token.isBlank()
                    && expiresAt != null
                    && OffsetDateTime.now().isBefore(expiresAt.minus(TOKEN_REFRESH_MARGIN));
        }
    }
}
