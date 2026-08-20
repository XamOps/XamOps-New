package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Client for Upwind's INTEGRATION service — a different host and a different audience from
 * {@link UpwindApiClient}, which talks to the data API.
 *
 * <h2>How these endpoints were found</h2>
 * Not from the docs: every {@code /cloud-accounts} and {@code /byoc-credentials} path guessed
 * against {@code api.upwind.io} returned 404 or 401, which an earlier parity audit misread as a
 * missing credential scope. The real routes were read out of Upwind's own onboarding Lambda
 * ({@code upwind-serverless-functions-<region>/onboarding/1.0.0.zip}, publicly readable), which
 * the CloudFormation template runs in the customer's account:
 *
 * <pre>
 *   POST   {base}/aws-external-ids       -&gt; mints the external id for a trust policy
 *   POST   {base}/cloud-credentials      -&gt; registers an account
 *   GET    {base}/cloud-credentials      -&gt; lists connected accounts, with status
 *   DELETE {base}/cloud-credentials/{id} -&gt; deregisters one account
 * </pre>
 *
 * where {@code base = https://integration.upwind.io/v1/organizations/{orgId}}, regionalised as
 * {@code .<region>.upwind.} for anything other than {@code us} — the same transformation the
 * Lambda applies.
 *
 * <h2>Why this matters for disconnection</h2>
 * The template's Delete handler is a no-op: it replies SUCCESS to CloudFormation and calls no
 * Upwind API, while logging "Integration deleted successfully". Deleting a customer's stack
 * therefore removes the IAM role and leaves the credential registered, so Upwind keeps trying to
 * assume a role that no longer exists. Proper removal requires the DELETE above, which is what
 * this client exists for.
 *
 * <h2>Credential</h2>
 * Uses the type {@code cloud_account} credential (the one that pre-fills customer stacks), NOT
 * the API sync credential — verified live: it carries create/get/delete:credentials and
 * get:external-ids on this audience, and zero permissions on the data API.
 */
@Component
@Slf4j
public class UpwindIntegrationClient {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Setter @Value("${upwind.onboarding.client-id:}")
    private String clientId;

    @Setter @Value("${upwind.onboarding.client-secret:}")
    private String clientSecret;

    @Setter @Value("${upwind.org-id:}")
    private String orgId;

    @Setter @Value("${upwind.token-url:https://auth.upwind.io/oauth/token}")
    private String tokenUrl;

    /** Audience AND host for this service; distinct from the data API's api.upwind.io. */
    @Setter @Value("${upwind.integration.base-url:https://integration.upwind.io}")
    private String integrationBaseUrl;

    @Setter @Value("${upwind.onboarding.region:us}")
    private String upwindRegion;

    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public UpwindIntegrationClient(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(orgId);
    }

    /** One registered cloud account, as Upwind reports it. */
    public record CloudCredential(String id, String name, String status, String cloudAccountId,
                                  boolean cloudScannerEnabled, boolean cloudApiEnabled) {
        /** Upwind's own verdict, rather than us inferring connectivity from asset counts. */
        public boolean connected() {
            return "connected".equalsIgnoreCase(status);
        }
    }

    /**
     * Every cloud account registered on the org.
     *
     * This is authoritative connection state — {@code status}, plus whether the scanner and cloud
     * API are actually enabled. Inferring "connected" from whether inventory assets exist cannot
     * distinguish "connected but scanner disabled" from "never connected", and reports a freshly
     * registered account as disconnected until its first scan lands.
     */
    public List<CloudCredential> listCloudCredentials() {
        String body = get(base() + "/cloud-credentials");
        List<CloudCredential> out = new ArrayList<>();
        try {
            for (JsonNode n : objectMapper.readTree(body)) {
                out.add(new CloudCredential(
                        text(n, "id"),
                        text(n, "name"),
                        text(n, "status"),
                        text(n.path("provider"), "account_id"),
                        n.path("cloudScannerEnabled").asBoolean(false),
                        n.path("cloudApiEnabled").asBoolean(false)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Upwind cloud-credentials list was not valid JSON", e);
        }
        return out;
    }

    /** The registration for one AWS account id, if the org has one. */
    public Optional<CloudCredential> findByCloudAccountId(String cloudAccountId) {
        return listCloudCredentials().stream()
                .filter(c -> cloudAccountId != null && cloudAccountId.equals(c.cloudAccountId()))
                .findFirst();
    }

    /**
     * Deregisters one account.
     *
     * @return true when Upwind no longer holds the registration — including when it already did
     *         not, since a 404 means the desired end state is already true and treating that as a
     *         failure would leave a customer unable to finish disconnecting.
     */
    public boolean deleteCloudCredential(String credentialId) {
        URI uri = URI.create(base() + "/cloud-credentials/" + credentialId);
        try {
            restTemplate.exchange(uri, HttpMethod.DELETE, new HttpEntity<>(authHeaders()),
                    String.class);
            log.info("[Upwind] deregistered cloud credential {}", credentialId);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            log.info("[Upwind] cloud credential {} was already absent", credentialId);
            return true;
        }
    }

    private String base() {
        return regional(integrationBaseUrl) + "/v1/organizations/" + orgId;
    }

    /**
     * Region rewrite, matching the Lambda's own {@code get_regional_url}: anything other than
     * {@code us} becomes {@code .<region>.upwind.}. Hardcoding the US host would silently talk to
     * the wrong region's org for an EU or ME tenant.
     */
    private String regional(String url) {
        String region = (upwindRegion == null || upwindRegion.isBlank()) ? "us" : upwindRegion;
        return "us".equals(region) ? url : url.replace(".upwind.", "." + region + ".upwind.");
    }

    private String get(String url) {
        ResponseEntity<String> resp = restTemplate.exchange(
                URI.create(url), HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        return resp.getBody();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getToken());
        // Upwind's WAF rejects default library user agents with an nginx 403.
        headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private synchronized String getToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return cachedToken;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        // Audience must be the integration host, not the data API — a token minted for
        // api.upwind.io authenticates here but carries zero permissions.
        form.add("audience", regional(integrationBaseUrl));
        ResponseEntity<String> resp = restTemplate.exchange(
                tokenUrl, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
        try {
            JsonNode body = objectMapper.readTree(resp.getBody());
            cachedToken = body.get("access_token").asText();
            long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 3600;
            tokenExpiry = Instant.now().plusSeconds(expiresIn);
            return cachedToken;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Upwind integration token", e);
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
