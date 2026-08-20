package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
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

    private final AzureClientProvider clientProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public AzureArmClient(AzureClientProvider clientProvider) {
        this.clientProvider = clientProvider;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
            return fresh.token();
        } catch (AzureArmException e) {
            throw e;
        } catch (Exception e) {
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
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                return Optional.of(response.body());
            }
            if (status == 404) {
                logger.debug("ARM GET {} returned 404", url);
                return Optional.empty();
            }
            // These are actionable configuration or capacity problems, not absent data.
            if (status == 401 || status == 403) {
                throw new AzureArmException(String.format(
                        "Azure denied access (HTTP %d). Check the service principal's role assignments. URL: %s",
                        status, url));
            }
            if (status == 429) {
                throw new AzureArmException("Azure throttled the request (HTTP 429). URL: " + url);
            }
            if (status >= 500) {
                throw new AzureArmException(
                        String.format("Azure returned HTTP %d for %s", status, url));
            }
            logger.warn("ARM GET {} returned unexpected HTTP {}", url, status);
            return Optional.empty();

        } catch (AzureArmException e) {
            throw e;
        } catch (Exception e) {
            throw new AzureArmException("ARM GET failed for " + url + ": " + e.getMessage(), e);
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
