package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP client for the Matters.ai All-Assets v1 API.
 *
 * <h2>Stateless by design</h2>
 * Unlike {@code UpwindApiClient}, which caches one OAuth bearer token in an instance field, this class
 * holds no credential state at all — every method takes a {@link MattersCredential}. It has to: there
 * is one key per customer, so an instance-level cached credential would be a cross-tenant bug waiting
 * to happen. Matters uses a static {@code X-API-Key} header with no token exchange, so there is
 * nothing worth caching anyway.
 *
 * <h2>Retry policy</h2>
 * Every call here runs inside a user request, which bounds what retrying can reasonably do.
 * <ul>
 *   <li><b>429</b> — honoured once, and only if the upstream {@code Retry-After} is short. The limit
 *       is 600 requests/minute <em>per key</em>, and keys are per-customer, so one customer cannot
 *       exhaust another's budget and a 429 here means this customer is genuinely hammering it. Waiting
 *       out a 30-second {@code Retry-After} on a request thread would be worse than reporting it, so
 *       anything above {@link #MAX_RETRY_WAIT_SECONDS} is passed to the caller as a 429 with the
 *       header intact.</li>
 *   <li><b>5xx and connection failures</b> — retried once after a short fixed delay. Transient vendor
 *       blips are the common case; a sustained outage should surface quickly rather than double every
 *       page load's latency.</li>
 *   <li><b>401, 403, 404</b> — never retried. None of them become true by trying again.</li>
 * </ul>
 *
 * <h2>What is never logged</h2>
 * {@link MattersCredential#apiKey()}, and any query string. Query strings carry customer search terms
 * and filter selections; the audit trail records the path only, for the same reason.
 */
@Component
@Slf4j
public class MattersApiClient {

    /** API version prefix. Every path below is relative to this. */
    private static final String API_PREFIX = "/api/v1";

    public static final String PATH_ASSETS = API_PREFIX + "/assets";
    public static final String PATH_FILTERS = API_PREFIX + "/assets/filters";
    public static final String PATH_SUMMARY = API_PREFIX + "/assets/summary";

    /**
     * The vendor's hard ceiling on {@code page_size}. Larger values are clamped upstream rather than
     * rejected, so clamping here too keeps our own pagination arithmetic honest.
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * Longest {@code Retry-After} we will wait out inline. Beyond this the 429 is returned to the
     * caller, because holding a request thread longer than the browser's own 45 s deadline achieves
     * nothing except occupying a thread.
     */
    private static final int MAX_RETRY_WAIT_SECONDS = 3;

    /** Fixed delay before the single retry of a 5xx or connection failure. */
    private static final long SERVER_ERROR_RETRY_DELAY_MS = 400L;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final MattersAuditService auditService;

    public MattersApiClient(@Qualifier("mattersRestTemplate") RestTemplate restTemplate,
                            ObjectMapper objectMapper,
                            MattersAuditService auditService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    /**
     * {@code GET /api/v1/assets} — the paginated, filterable asset list.
     *
     * @param query already-validated query parameters. Multi-value entries are passed as
     *              comma-separated values, which the API treats as OR within a dimension and AND
     *              across dimensions.
     */
    public JsonNode listAssets(MattersCredential credential, Map<String, List<String>> query) {
        return get(credential, PATH_ASSETS, query);
    }

    /** {@code GET /api/v1/assets/filters} — faceted filter options with counts. */
    public JsonNode listFilters(MattersCredential credential, Map<String, List<String>> query) {
        return get(credential, PATH_FILTERS, query);
    }

    /** {@code GET /api/v1/assets/summary} — headline counts and 14-day trends. */
    public JsonNode summary(MattersCredential credential, String category) {
        Map<String, List<String>> query = (category == null || category.isBlank())
                ? Map.of()
                : Map.of("category", List.of(category));
        return get(credential, PATH_SUMMARY, query);
    }

    /**
     * {@code GET /api/v1/assets/{id}} — one asset in full, with {@code facet} and {@code objects}.
     *
     * A non-UUID id returns 404 from the vendor rather than a validation error, and an id belonging to
     * another project is indistinguishable from an unknown one — which is the correct behaviour, and
     * is why {@link MattersApiException.Reason#ASSET_NOT_FOUND} carries no hint about why.
     */
    public JsonNode getAsset(MattersCredential credential, String assetId) {
        return get(credential, PATH_ASSETS + "/" + assetId, Map.of());
    }

    /**
     * Cheapest possible authenticated call, for the admin Verify button.
     *
     * Uses {@code page_size=1} on the list rather than the summary endpoint: it exercises the same
     * auth path for a single row, and its {@code pagination.total} confirms the key resolves to a
     * project with data.
     */
    public JsonNode verify(MattersCredential credential) {
        return get(credential, PATH_ASSETS, Map.of("page_size", List.of("1")));
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private JsonNode get(MattersCredential credential, String path, Map<String, List<String>> query) {
        URI uri = buildUri(credential.baseUrl(), path, query);
        long startedAt = System.currentTimeMillis();
        try {
            JsonNode body = exchange(uri, credential, path, false);
            auditService.recordCredentialUse(credential.clientId(), credential.keyId(),
                    credential.projectId(), path, System.currentTimeMillis() - startedAt);
            return body;
        } catch (MattersApiException e) {
            auditService.recordCredentialFailure(credential.clientId(), credential.keyId(),
                    credential.projectId(), path, e.getReason().name(), e.getMessage());
            throw e;
        }
    }

    /**
     * Issues the request, translating transport and status failures into
     * {@link MattersApiException}.
     *
     * @param isRetry guards the single-retry policy — a retried call that fails again propagates
     *                rather than recursing.
     */
    private JsonNode exchange(URI uri, MattersCredential credential, String path, boolean isRetry) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(authHeaders(credential)), String.class);
            return parse(response.getBody(), path);

        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();

            if (status.value() == 429) {
                int retryAfter = retryAfterSeconds(e);
                if (!isRetry && retryAfter > 0 && retryAfter <= MAX_RETRY_WAIT_SECONDS) {
                    sleep(retryAfter * 1000L);
                    return exchange(uri, credential, path, true);
                }
                throw new MattersApiException(MattersApiException.Reason.RATE_LIMITED,
                        "Matters rate limit exceeded for this organisation's API key",
                        retryAfter, e);
            }

            if (status.value() == 401) {
                throw unauthorized(e);
            }
            if (status.value() == 403) {
                throw new MattersApiException(MattersApiException.Reason.FORBIDDEN_SCOPE,
                        "The Matters API key is not permitted for this action — it likely lacks the "
                                + "PLATFORM scope", e);
            }
            if (status.value() == 404) {
                throw new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND,
                        "Asset not found", e);
            }

            if (status.is5xxServerError()) {
                if (!isRetry) {
                    sleep(SERVER_ERROR_RETRY_DELAY_MS);
                    return exchange(uri, credential, path, true);
                }
                throw new MattersApiException(MattersApiException.Reason.UPSTREAM_UNAVAILABLE,
                        "Matters returned " + status.value() + " for " + path, e);
            }

            // 4xx we do not recognise. Reported as a bad upstream response rather than mapped onto a
            // credential problem — guessing here is how a working key gets marked invalid.
            throw new MattersApiException(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE,
                    "Matters returned an unexpected " + status.value() + " for " + path, e);

        } catch (ResourceAccessException e) {
            // Connect timeout, read timeout, DNS or TLS failure — the tenant is unreachable.
            if (!isRetry) {
                sleep(SERVER_ERROR_RETRY_DELAY_MS);
                return exchange(uri, credential, path, true);
            }
            throw new MattersApiException(MattersApiException.Reason.UPSTREAM_UNAVAILABLE,
                    "Could not reach Matters: " + e.getMostSpecificCause().getMessage(), e);
        }
    }

    /**
     * Distinguishes the 401 causes, which the vendor separates by message.
     *
     * Worth the string matching: "wrong environment" means the base URL is wrong and the key is fine,
     * while the others mean the key needs rotating. Sending an operator to rotate a perfectly good key
     * is the failure this avoids. An unrecognised message falls back to INVALID_KEY, which is the
     * common case.
     */
    private MattersApiException unauthorized(RestClientResponseException e) {
        String message = vendorMessage(e).toLowerCase(Locale.ROOT);
        if (message.contains("different environment")) {
            return new MattersApiException(MattersApiException.Reason.WRONG_ENVIRONMENT,
                    "The API key was issued for a different Matters environment than the configured "
                            + "base URL", e);
        }
        if (message.contains("expired") || message.contains("revoked")) {
            return new MattersApiException(MattersApiException.Reason.KEY_EXPIRED,
                    "The Matters API key has been revoked or has expired", e);
        }
        return new MattersApiException(MattersApiException.Reason.INVALID_KEY,
                "Matters rejected the API key", e);
    }

    /** Pulls {@code message} out of the vendor's {@code {"success":false,"message":"..."}} body. */
    private String vendorMessage(RestClientResponseException e) {
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsString());
            String message = body.path("message").asText(null);
            if (message == null) {
                message = body.path("error").asText("");
            }
            return message == null ? "" : message;
        } catch (Exception ignored) {
            return "";
        }
    }

    private int retryAfterSeconds(RestClientResponseException e) {
        HttpHeaders headers = e.getResponseHeaders();
        if (headers == null) {
            return 0;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ex) {
            // Retry-After may also be an HTTP date. Not parsed: the vendor documents and sends
            // seconds, and a wrong guess here would mean sleeping for an arbitrary period.
            return 0;
        }
    }

    private JsonNode parse(String body, String path) {
        if (body == null || body.isBlank()) {
            throw new MattersApiException(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE,
                    "Matters returned an empty body for " + path);
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new MattersApiException(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE,
                    "Matters returned a body that could not be parsed for " + path, e);
        }
    }

    private HttpHeaders authHeaders(MattersCredential credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", credential.apiKey());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        // Identifies us in the vendor's logs, which matters when diagnosing a rate limit with them.
        headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
        return headers;
    }

    /**
     * Builds the URI, encoding every value.
     *
     * {@code build()} without {@code encode()} would pass a customer's search term through raw, so a
     * {@code q} containing a space or ampersand would either fail or silently truncate the query.
     */
    private URI buildUri(String baseUrl, String path, Map<String, List<String>> query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl).path(path);
        query.forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                builder.queryParam(key, values.toArray());
            }
        });
        return builder.encode().build().toUri();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it, so a shutdown interrupt is not lost and
            // whatever is above this on the stack can still observe it.
            Thread.currentThread().interrupt();
            throw new MattersApiException(MattersApiException.Reason.UPSTREAM_UNAVAILABLE,
                    "Interrupted while waiting to retry a Matters request", e);
        }
    }
}
