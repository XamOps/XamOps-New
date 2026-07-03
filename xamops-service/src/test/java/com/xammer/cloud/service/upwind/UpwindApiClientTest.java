package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpwindApiClientTest {

    private RestTemplate restTemplate;
    private UpwindApiClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new UpwindApiClient(new ObjectMapper(), restTemplate);
        client.setClientId("test-id");
        client.setClientSecret("test-secret");
        client.setOrgId("org_test");
        client.setTokenUrl("https://oauth.example/token");
        client.setApiBaseUrl("https://api.example");
        client.setAudience("https://api.example");
    }

    private void mockToken() {
        ResponseEntity<String> tokenResp = ResponseEntity.ok(
                "{\"access_token\":\"tok-123\",\"expires_in\":86400}");
        when(restTemplate.exchange(eq("https://oauth.example/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(tokenResp);
    }

    @Test
    void isConfiguredFalseWhenNoCredentials() {
        client.setClientId("");
        assertFalse(client.isConfigured());
    }

    @Test
    void fetchAllFollowsLinkHeaderPagination() {
        mockToken();
        HttpHeaders page1Headers = new HttpHeaders();
        page1Headers.add("Link",
                "<https://api.example/v1/organizations/org_test/threat-detections?page-token=abc>; rel=\"next\"");
        ResponseEntity<String> page1 = new ResponseEntity<>(
                "[{\"id\":\"a\"}]", page1Headers, org.springframework.http.HttpStatus.PARTIAL_CONTENT);
        ResponseEntity<String> page2 = ResponseEntity.ok("[{\"id\":\"b\"}]");

        when(restTemplate.exchange(
                eq(URI.create("https://api.example/v1/organizations/org_test/threat-detections")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(page1);
        when(restTemplate.exchange(
                eq(URI.create("https://api.example/v1/organizations/org_test/threat-detections?page-token=abc")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(page2);

        List<JsonNode> all = client.fetchThreatDetections(10);
        assertEquals(2, all.size());
        assertEquals("a", all.get(0).get("id").asText());
        assertEquals("b", all.get(1).get("id").asText());
    }

    @Test
    void encodedPageTokenIsNotDoubleEncoded() {
        // Upwind's Link header next-URLs are already percent-encoded; passing them
        // through the String overload re-encodes (%5B -> %255B) and Upwind 400s.
        mockToken();
        String encodedNext =
                "https://api.example/v1/organizations/org_test/vulnerability-findings?per-page=100&page-token=%5B%22uvf-02ec%22%5D";
        HttpHeaders page1Headers = new HttpHeaders();
        page1Headers.add("Link", "<" + encodedNext + ">; rel=\"next\"");
        ResponseEntity<String> page1 = new ResponseEntity<>(
                "[{\"id\":\"a\"}]", page1Headers, org.springframework.http.HttpStatus.PARTIAL_CONTENT);
        ResponseEntity<String> page2 = ResponseEntity.ok("[{\"id\":\"b\"}]");

        when(restTemplate.exchange(
                eq(URI.create("https://api.example/v1/organizations/org_test/vulnerability-findings")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(page1);
        // The already-encoded URI must be passed through verbatim
        when(restTemplate.exchange(
                eq(URI.create(encodedNext)),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(page2);

        List<JsonNode> all = client.fetchVulnerabilityFindings(10);
        assertEquals(2, all.size());
        assertEquals("b", all.get(1).get("id").asText());
    }

    @Test
    void fetchAllStopsAtMaxPages() {
        mockToken();
        HttpHeaders loopHeaders = new HttpHeaders();
        loopHeaders.add("Link",
                "<https://api.example/v1/organizations/org_test/threat-detections?page-token=again>; rel=\"next\"");
        ResponseEntity<String> looping = new ResponseEntity<>(
                "[{\"id\":\"x\"}]", loopHeaders, org.springframework.http.HttpStatus.PARTIAL_CONTENT);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(looping);

        List<JsonNode> all = client.fetchThreatDetections(3);
        assertEquals(3, all.size());
    }

    @Test
    void tokenIsReusedAcrossCalls() {
        mockToken();
        ResponseEntity<String> page = ResponseEntity.ok("[]");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class))).thenReturn(page);

        client.fetchThreatDetections(1);
        client.fetchConfigurationFindings(1);

        verify(restTemplate, times(1)).exchange(eq("https://oauth.example/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }
}
