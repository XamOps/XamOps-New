package com.xammer.cloud.service.war.azure;

import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.azure.AzureClientProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin ARM REST helper for the Azure WAR checks.
 *
 * <p>
 * The fluent {@code AzureResourceManager} SDK covers most resources, but several controls
 * depend on services it does not model: Defender for Cloud pricing tiers, policy
 * assignments, Monitor alert rules, and Consumption budgets. Rather than add four more
 * SDK dependencies for a handful of read-only GETs, those go over ARM directly — the same
 * approach {@code AzureSecurityService} already takes.
 */
@Component
public class AzureWarRest {

    private static final String ARM = "https://management.azure.com";
    private static final String SCOPE = "https://management.azure.com/.default";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final AzureClientProvider clientProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    public AzureWarRest(AzureClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    /**
     * GETs a subscription-scoped ARM path and returns the {@code value} array.
     *
     * <p>
     * Throws on any non-2xx so the caller's {@code guard} records the real cause. A 403
     * here usually means the service principal lacks Reader on the subscription, which is
     * a materially different problem from a control actually failing.
     */
    public List<JsonNode> listValues(String subscriptionId, String path) {
        JsonNode body = get(subscriptionId, path);
        List<JsonNode> out = new ArrayList<>();
        JsonNode value = body.get("value");
        if (value != null && value.isArray()) {
            value.forEach(out::add);
        }
        return out;
    }

    public JsonNode get(String subscriptionId, String path) {
        String token = clientProvider.getCredential(subscriptionId)
                .getToken(new TokenRequestContext().addScopes(SCOPE))
                .block(TIMEOUT)
                .getToken();

        String uri = path.startsWith("http")
                ? path
                : ARM + "/subscriptions/" + subscriptionId + path;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(String.format(
                        "ARM returned %d for %s: %s",
                        response.statusCode(), path, summarise(response.body())));
            }
            return objectMapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling " + path, e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * ARM error bodies carry the useful message inside {@code error.message}; the raw
     * JSON is noise in a UI card.
     */
    private String summarise(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String message = error.path("message").asText("");
            if (!message.isBlank()) {
                return message.length() > 240 ? message.substring(0, 240) : message;
            }
        } catch (Exception ignored) {
            // Fall through to the raw body.
        }
        return body == null || body.length() <= 240 ? String.valueOf(body) : body.substring(0, 240);
    }
}
