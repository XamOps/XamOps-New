package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.identity.AuthentikGroupDto;
import com.xammer.cloud.dto.identity.AuthentikPagedResponse;
import com.xammer.cloud.dto.identity.AuthentikUserDto;
import com.xammer.cloud.exception.AuthentikUnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Low-level HTTP client for the Authentik REST API.
 *
 * Authentik API base: https://auth.xamops.com/api/v3/
 * Authentik API docs: https://docs.goauthentik.io/developer-docs/api/
 *
 * Design:
 * - Stateless: the caller injects the per-tenant Bearer token on every call.
 *   Tokens are never cached in this bean — they are decrypted in IdentityService
 *   and passed in directly.
 * - Auto-pagination: list methods follow Authentik's `pagination.next` field
 *   until all results are collected (page_size=100 per request).
 * - Retry: 429 responses trigger exponential back-off with jitter (max 3 retries).
 * - 401 surfaces as AuthentikUnauthorizedException; caller handles it.
 * - Token values are never written to logs.
 */
@Component
public class AuthentikApiClient {

    private static final Logger log = LoggerFactory.getLogger(AuthentikApiClient.class);

    private static final int PAGE_SIZE = 100;
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 500;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public AuthentikApiClient(
            @Qualifier("authentikRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${authentik.base-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    /**
     * List all users whose username starts with the given prefix.
     * Authentik endpoint: GET /api/v3/core/users/?search={prefix}&page_size=100
     *
     * Results are auto-paginated until all pages are collected.
     * The caller must additionally filter server-side to enforce prefix isolation.
     */
    public List<AuthentikUserDto> listUsers(String token, String prefix, Long clientId) {
        String url = baseUrl + "/api/v3/core/users/";
        return fetchAllPages(token, url, "search", prefix, clientId,
                new TypeReference<AuthentikPagedResponse<AuthentikUserDto>>() {});
    }

    /**
     * Create a new user.
     * Authentik endpoint: POST /api/v3/core/users/
     *
     * Per Authentik best practice: username MUST equal email for SAML/SCIM attribute mapping.
     */
    public AuthentikUserDto createUser(String token, String name, String email, Long clientId) {
        String url = baseUrl + "/api/v3/core/users/";
        Map<String, Object> body = Map.of(
                "username", email,
                "email", email,
                "name", name,
                "is_active", true
        );
        ResponseEntity<AuthentikUserDto> response =
                executeWithRetry(token, HttpMethod.POST, url, body, AuthentikUserDto.class, clientId);
        return response.getBody();
    }

    /**
     * Deactivate a user by setting is_active=false.
     * Authentik endpoint: PATCH /api/v3/core/users/{id}/
     *
     * Per Authentik best practice: users are NEVER deleted — only deactivated.
     * This preserves audit trail and prevents orphaned SCIM references.
     */
    public AuthentikUserDto deactivateUser(String token, Long authentikUserId, Long clientId) {
        String url = baseUrl + "/api/v3/core/users/" + authentikUserId + "/";
        Map<String, Object> body = Map.of("is_active", false);
        ResponseEntity<AuthentikUserDto> response =
                executeWithRetry(token, HttpMethod.PATCH, url, body, AuthentikUserDto.class, clientId);
        return response.getBody();
    }

    /**
     * Send a recovery/invite email so the new user can self-serve set their password.
     * Authentik endpoint: POST /api/v3/core/users/{id}/recovery_email/
     *
     * This is the official Authentik onboarding flow — no manual password is set.
     * The user receives a link from Authentik to configure their own password.
     */
    public void sendRecoveryEmail(String token, Long authentikUserId, Long clientId) {
        String url = baseUrl + "/api/v3/core/users/" + authentikUserId + "/recovery_email/";
        executeWithRetry(token, HttpMethod.POST, url, Map.of(), Void.class, clientId);
    }

    // ── Groups ────────────────────────────────────────────────────────────────

    /**
     * List all groups whose name starts with the given prefix.
     * Authentik endpoint: GET /api/v3/core/groups/?name__startswith={prefix}
     */
    public List<AuthentikGroupDto> listGroups(String token, String prefix, Long clientId) {
        String url = baseUrl + "/api/v3/core/groups/";
        return fetchAllPages(token, url, "name__startswith", prefix, clientId,
                new TypeReference<AuthentikPagedResponse<AuthentikGroupDto>>() {});
    }

    /**
     * Add a user to a group.
     * Authentik endpoint: POST /api/v3/core/groups/{groupId}/add_user/
     *
     * Adding a user to an Authentik group that is synced via SCIM to AWS IAM
     * Identity Center automatically grants AWS console access (within seconds).
     */
    public void addUserToGroup(String token, String groupId, Long userId, Long clientId) {
        String url = baseUrl + "/api/v3/core/groups/" + groupId + "/add_user/";
        executeWithRetry(token, HttpMethod.POST, url, Map.of("pk", userId), Void.class, clientId);
    }

    /**
     * Remove a user from a group.
     * Authentik endpoint: POST /api/v3/core/groups/{groupId}/remove_user/
     *
     * Removing a user from a SCIM-synced group revokes their AWS SSO access automatically.
     */
    public void removeUserFromGroup(String token, String groupId, Long userId, Long clientId) {
        String url = baseUrl + "/api/v3/core/groups/" + groupId + "/remove_user/";
        executeWithRetry(token, HttpMethod.POST, url, Map.of("pk", userId), Void.class, clientId);
    }

    // ── SCIM sync fallback ────────────────────────────────────────────────────

    /**
     * Trigger a manual SCIM sync for a given SCIM provider.
     * Authentik endpoint: GET /api/v3/providers/scim/{id}/sync/
     *
     * Not required in normal operation — SCIM syncs automatically on group changes.
     * Exposed here for admin-triggered fallback only.
     */
    public void triggerScimSync(String token, Long scimProviderId, Long clientId) {
        String url = baseUrl + "/api/v3/providers/scim/" + scimProviderId + "/sync/";
        executeWithRetry(token, HttpMethod.GET, url, null, Void.class, clientId);
    }

    // ── Master-token provisioning ops ────────────────────────────────────────

    /**
     * Create a group using the master token (tenant provisioning only).
     * Authentik endpoint: POST /api/v3/core/groups/
     */
    public AuthentikGroupDto createGroup(String token, String groupName, Long clientId) {
        String url = baseUrl + "/api/v3/core/groups/";
        Map<String, Object> body = Map.of("name", groupName);
        ResponseEntity<AuthentikGroupDto> response =
                executeWithRetry(token, HttpMethod.POST, url, body, AuthentikGroupDto.class, clientId);
        return response.getBody();
    }

    /**
     * Create an API token for a service account user (tenant provisioning).
     * Authentik endpoint: POST /api/v3/core/tokens/
     */
    public String createServiceToken(String masterToken, Long serviceAccountPk, String identifier) {
        String url = baseUrl + "/api/v3/core/tokens/";
        Map<String, Object> body = Map.of(
                "identifier", identifier,
                "user", serviceAccountPk,
                "intent", "api"
        );
        ResponseEntity<Map> response =
                executeWithRetry(masterToken, HttpMethod.POST, url, body, Map.class, null);
        Object key = response.getBody() != null ? response.getBody().get("key") : null;
        return key != null ? key.toString() : null;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Fetches all pages of a list endpoint, following Authentik's pagination.next pointer.
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> fetchAllPages(String token, String baseEndpoint,
                                       String filterParam, String filterValue,
                                       Long clientId,
                                       TypeReference<AuthentikPagedResponse<T>> typeRef) {
        List<T> all = new ArrayList<>();
        int page = 1;

        while (true) {
            String url = UriComponentsBuilder.fromUriString(baseEndpoint)
                    .queryParam(filterParam, filterValue)
                    .queryParam("page_size", PAGE_SIZE)
                    .queryParam("page", page)
                    .toUriString();

            ResponseEntity<String> raw =
                    executeWithRetry(token, HttpMethod.GET, url, null, String.class, clientId);

            if (raw.getBody() == null) break;

            try {
                AuthentikPagedResponse<T> paged = objectMapper.readValue(raw.getBody(), typeRef);
                if (paged.getResults() != null) {
                    all.addAll(paged.getResults());
                }
                if (paged.getPagination() == null || paged.getPagination().getNext() == 0) {
                    break;
                }
                page = paged.getPagination().getNext();
            } catch (Exception e) {
                log.error("Failed to parse Authentik paginated response (page {}): {}", page, e.getMessage());
                break;
            }
        }
        return all;
    }

    /**
     * Executes an HTTP request with exponential back-off retry on 429.
     *
     * Retry schedule (with ±10 % jitter):
     *   Attempt 1: immediate
     *   Attempt 2: ~500 ms
     *   Attempt 3: ~1 000 ms
     *   Attempt 4: ~2 000 ms → give up, surface error
     *
     * 401 → AuthentikUnauthorizedException (token invalid/revoked).
     *        Token value is NEVER included in any log or exception message.
     */
    private <T> ResponseEntity<T> executeWithRetry(String token, HttpMethod method,
                                                    String url, Object body,
                                                    Class<T> responseType, Long clientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Object> entity = (body != null) ? new HttpEntity<>(body, headers)
                                                    : new HttpEntity<>(headers);

        int attempt = 0;
        while (attempt <= MAX_RETRIES) {
            try {
                return restTemplate.exchange(url, method, entity, responseType);

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                    // 401 — token invalid or revoked. Never log the token.
                    log.error("Authentik returned 401 for client {}. Token re-provisioning required.", clientId);
                    throw new AuthentikUnauthorizedException(clientId);
                }

                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    attempt++;
                    if (attempt > MAX_RETRIES) {
                        log.warn("Authentik rate limit exceeded after {} retries for URL: {}", MAX_RETRIES, url);
                        throw e;
                    }
                    long backoff = (long) (BASE_BACKOFF_MS * Math.pow(2, attempt - 1)
                                          * (0.9 + Math.random() * 0.2));
                    log.warn("Authentik 429 — retrying in {} ms (attempt {}/{})", backoff, attempt, MAX_RETRIES);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted during Authentik retry back-off", ie);
                    }
                    continue;
                }

                // All other 4xx/5xx — surface immediately
                log.error("Authentik API error {} for {} {}: {}",
                        e.getStatusCode(), method, url, e.getResponseBodyAsString());
                throw e;

            } catch (Exception e) {
                log.error("Authentik API call failed for {} {}: {}", method, url, e.getMessage());
                throw e;
            }
        }

        throw new IllegalStateException("Unreachable: retry loop exited without returning");
    }
}
