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

    @Test
    void searchInventoryFollowsCursorAndSendsUserAgent() {
        String base = "https://api.example/v2/organizations/org_test/inventory/assets/search";
        ResponseEntity<String> page1 = ResponseEntity.ok(
                "{\"items\":[{\"id\":\"a\"}],\"metadata\":{\"next_cursor\":\"c1\"}}");
        ResponseEntity<String> page2 = ResponseEntity.ok(
                "{\"items\":[{\"id\":\"b\"}],\"metadata\":{}}");
        when(restTemplate.exchange(eq(URI.create(base + "?limit=100")), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class))).thenReturn(page1);
        when(restTemplate.exchange(eq(URI.create(base + "?limit=100&cursor=c1")), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class))).thenReturn(page2);

        List<JsonNode> items = client.searchInventory(
                List.of(Map.of("field", "cloud_provider", "operator", "eq", "value", List.of("AWS"))), 1000);
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).get("id").asText());
        assertEquals("b", items.get(1).get("id").asText());
    }

    @Test
    void fetchApiSecurityEndpointsPassesCloudAccountIdAndPages() {
        HttpHeaders h1 = new HttpHeaders();
        h1.add("Link", "<https://api.example/v1/organizations/org_test/apisecurity-endpoints?cloud-account-id=111&page-token=t2>; rel=\"next\"");
        ResponseEntity<String> p1 = new ResponseEntity<>("[{\"id\":\"e1\"}]", h1, HttpStatus.PARTIAL_CONTENT);
        ResponseEntity<String> p2 = ResponseEntity.ok("[{\"id\":\"e2\"}]");
        when(restTemplate.exchange(eq(URI.create(
                "https://api.example/v1/organizations/org_test/apisecurity-endpoints?cloud-account-id=111&per-page=1000")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class))).thenReturn(p1);
        when(restTemplate.exchange(eq(URI.create(
                "https://api.example/v1/organizations/org_test/apisecurity-endpoints?cloud-account-id=111&page-token=t2")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class))).thenReturn(p2);

        List<JsonNode> items = client.fetchApiSecurityEndpoints("111", 10);
        assertEquals(2, items.size());
        assertEquals("e2", items.get(1).get("id").asText());
    }

    @Test
    void createWebhookPostsBodyAndReturnsJson() {
        ObjectMapper m = new ObjectMapper();
        JsonNode body = m.createObjectNode().put("name", "xamops");
        when(restTemplate.exchange(eq(URI.create(
                "https://api.example/v1/organizations/org_test/integration-webhooks")),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"wh1\",\"name\":\"xamops\"}"));
        JsonNode created = client.createWebhook(body);
        assertEquals("wh1", created.get("id").asText());
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
