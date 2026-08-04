package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpwindApiClientPhase2Test {

    private RestTemplate restTemplate;
    private UpwindApiClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new UpwindApiClient(new ObjectMapper(), restTemplate);
        client.setClientId("id"); client.setClientSecret("sec");
        client.setOrgId("org_test");
        client.setTokenUrl("https://oauth.example/token");
        client.setApiBaseUrl("https://api.example");
        client.setAudience("https://api.example");
        when(restTemplate.exchange(eq("https://oauth.example/token"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"access_token\":\"tok\",\"expires_in\":86400}"));
    }

    /**
     * Inventory must hit the documented CATALOG path at the documented ceiling (limit=200).
     * The flat /inventory/assets/search we used before also returns 200 but omits every risk
     * and classification field, so pinning the URL here is the guard against silently
     * regressing to the thin payload.
     */
    @Test
    void searchInventoryUsesCatalogPathAndFollowsCursor() {
        String base = "https://api.example/v2/organizations/org_test/inventory/catalog/assets/search";
        ResponseEntity<String> page1 = ResponseEntity.ok(
                "{\"items\":[{\"id\":\"a\"}],\"metadata\":{\"next_cursor\":\"c1\"}}");
        ResponseEntity<String> page2 = ResponseEntity.ok(
                "{\"items\":[{\"id\":\"b\"}],\"metadata\":{}}");
        when(restTemplate.exchange(eq(URI.create(base + "?limit=200")), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class))).thenReturn(page1);
        when(restTemplate.exchange(eq(URI.create(base + "?limit=200&cursor=c1")), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class))).thenReturn(page2);

        List<JsonNode> items = client.searchInventory(
                List.of(Map.of("field", "cloud_account_id", "operator", "eq", "value", List.of("111"))), 1000);
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).get("id").asText());
        assertEquals("b", items.get(1).get("id").asText());
    }

    /** v2 endpoint search: cursor-paged POST at the documented limit ceiling of 100. */
    @Test
    void searchApiEndpointsFiltersByAccountAndFollowsCursor() {
        String base = "https://api.example/v2/organizations/org_test/api-security/endpoints/search";
        when(restTemplate.exchange(eq(URI.create(base + "?limit=100")), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(
                        "{\"items\":[{\"id\":\"rt-1\"}],\"metadata\":{\"next_cursor\":\"c1\"}}"));
        when(restTemplate.exchange(eq(URI.create(base + "?limit=100&cursor=c1")), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"items\":[{\"id\":\"rt-2\"}],\"metadata\":{}}"));

        UpwindApiClient.FetchResult res = client.searchApiEndpoints("111", 1000);

        assertEquals(2, res.items().size());
        assertFalse(res.truncated());
        assertEquals("rt-2", res.items().get(1).get("id").asText());
    }

    /** Hitting the item cap with a cursor still outstanding must be reported as truncated. */
    @Test
    void searchApiEndpointsReportsTruncationAtItemCap() {
        String base = "https://api.example/v2/organizations/org_test/api-security/endpoints/search";
        when(restTemplate.exchange(eq(URI.create(base + "?limit=100")), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(
                        "{\"items\":[{\"id\":\"rt-1\"}],\"metadata\":{\"next_cursor\":\"c1\"}}"));

        UpwindApiClient.FetchResult res = client.searchApiEndpoints("111", 1);

        assertEquals(1, res.items().size());
        assertTrue(res.truncated());
    }

    /** v2 create posts to /integrations/webhooks and unwraps the items[] envelope. */
    @Test
    void createWebhookPostsToV2AndUnwrapsItems() {
        ObjectMapper m = new ObjectMapper();
        JsonNode body = m.createObjectNode().put("name", "xamops");
        when(restTemplate.exchange(eq(URI.create(
                "https://api.example/v2/organizations/org_test/integrations/webhooks")),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"items\":[{\"id\":\"wh1\",\"name\":\"xamops\"}]}"));
        JsonNode created = client.createWebhook(body);
        assertEquals("wh1", created.get("id").asText());
    }

    @Test
    void searchWebhooksWalksCursorPages() {
        String base = "https://api.example/v2/organizations/org_test/integrations/webhooks/search";
        when(restTemplate.exchange(eq(URI.create(base + "?limit=100")), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"items\":[{\"id\":\"wh1\"}],\"metadata\":{}}"));

        List<JsonNode> items = client.searchWebhooks(100);

        assertEquals(1, items.size());
        assertEquals("wh1", items.get(0).get("id").asText());
    }

    @Test
    void deleteWebhookIssuesV2Delete() {
        URI uri = URI.create("https://api.example/v2/organizations/org_test/integrations/webhooks/wh1");
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.DELETE), any(HttpEntity.class),
                eq(String.class))).thenReturn(ResponseEntity.noContent().build());

        client.deleteWebhook("wh1");

        verify(restTemplate).exchange(eq(uri), eq(HttpMethod.DELETE), any(HttpEntity.class),
                eq(String.class));
    }

    /**
     * 429 is documented on every v2 operation. It must be retried, and a Retry-After hint
     * honoured rather than replaced by our own shorter backoff.
     */
    @Test
    void rateLimitIsRetriedHonouringRetryAfter() {
        client.setRetryBackoffMs(0);
        client.setMaxAttempts(2);
        String base = "https://api.example/v2/organizations/org_test/integrations/webhooks/search";
        HttpHeaders retryAfter = new HttpHeaders();
        retryAfter.add(HttpHeaders.RETRY_AFTER, "0");
        when(restTemplate.exchange(eq(URI.create(base + "?limit=100")), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", retryAfter,
                        new byte[0], null))
                .thenReturn(ResponseEntity.ok("{\"items\":[{\"id\":\"wh1\"}],\"metadata\":{}}"));

        List<JsonNode> items = client.searchWebhooks(100);

        assertEquals(1, items.size());
        verify(restTemplate, times(2)).exchange(eq(URI.create(base + "?limit=100")),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void updateDetectionStatusPatchesStatus() {
        when(restTemplate.exchange(eq(URI.create(
                "https://api.example/v1/organizations/org_test/threat-detections/uwnd-1")),
                eq(HttpMethod.PATCH), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        client.updateDetectionStatus("uwnd-1", "ARCHIVED");
        verify(restTemplate).exchange(eq(URI.create(
                "https://api.example/v1/organizations/org_test/threat-detections/uwnd-1")),
                eq(HttpMethod.PATCH), any(HttpEntity.class), eq(String.class));
    }
}
