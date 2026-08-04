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

    /** First-page URL for a v1 list endpoint, including the requested page size. */
    private static URI firstPage(String path) {
        return URI.create("https://api.example/v1/organizations/org_test" + path
                + "?per-page=" + UpwindApiClient.PAGE_SIZE);
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

        when(restTemplate.exchange(eq(firstPage("/threat-detections")),
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

        when(restTemplate.exchange(eq(firstPage("/vulnerability-findings")),
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
    void followsNextLinkWhenPageTokenContainsRawComma() {
        // Upwind's configuration-findings page-token embeds an UNENCODED comma, e.g.
        // page-token=%5B1785448068963,%22fmw-…%22%5D. Splitting the Link header on ','
        // tore the URL apart, so paging stopped after page 1 and the full sync then
        // archived every finding it never re-fetched.
        mockToken();
        String commaNext = "https://api.example/v1/organizations/org_test/configuration-findings"
                + "?per-page=100&page-token=%5B1785448068963,%22fmw-1e99::605134457560::9415%22%5D";
        HttpHeaders page1Headers = new HttpHeaders();
        page1Headers.add("Link",
                "<https://api.example/v1/organizations/org_test/configuration-findings?per-page=100>; rel=\"first\", "
                        + "<" + commaNext + ">; rel=\"next\"");
        ResponseEntity<String> page1 = new ResponseEntity<>(
                "[{\"id\":\"a\"}]", page1Headers, org.springframework.http.HttpStatus.OK);
        ResponseEntity<String> page2 = ResponseEntity.ok("[{\"id\":\"b\"}]");

        when(restTemplate.exchange(eq(firstPage("/configuration-findings")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(page1);
        when(restTemplate.exchange(eq(URI.create(commaNext)),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(page2);

        UpwindApiClient.FetchResult res = client.fetchConfigurationFindingsPaged(10);
        assertEquals(2, res.items().size(), "next page must be followed despite the raw comma");
        assertEquals("b", res.items().get(1).get("id").asText());
        assertFalse(res.truncated(), "walk ended because no next link remained");
    }

    @Test
    void requestsLargePagesToStayUnderThePageCap() {
        // At Upwind's default 100/page a full configuration-findings walk needed 196 of the
        // 200 allowed pages; 1000/page keeps it near 20.
        mockToken();
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class))).thenReturn(ResponseEntity.ok("[]"));

        client.fetchConfigurationFindings(1);

        verify(restTemplate).exchange(eq(firstPage("/configuration-findings")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void truncatedFetchIsReportedWhenPageCapIsHit() {
        mockToken();
        HttpHeaders loopHeaders = new HttpHeaders();
        loopHeaders.add("Link",
                "<https://api.example/v1/organizations/org_test/configuration-findings?page-token=again>; rel=\"next\"");
        ResponseEntity<String> looping = new ResponseEntity<>(
                "[{\"id\":\"x\"}]", loopHeaders, org.springframework.http.HttpStatus.PARTIAL_CONTENT);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(looping);

        UpwindApiClient.FetchResult res = client.fetchConfigurationFindingsPaged(2);
        assertEquals(2, res.items().size());
        assertTrue(res.truncated(), "hitting the cap with a next link left must flag incompleteness");
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
    void retriesOn5xxThenSucceeds() {
        mockToken();
        client.setMaxAttempts(3);
        client.setRetryBackoffMs(1); // keep the test fast
        URI uri = firstPage("/threat-detections");
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new org.springframework.web.client.HttpServerErrorException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .thenThrow(new org.springframework.web.client.HttpServerErrorException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .thenReturn(ResponseEntity.ok("[{\"id\":\"a\"}]"));

        List<JsonNode> all = client.fetchThreatDetections(1);
        assertEquals(1, all.size());
        verify(restTemplate, times(3)).exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void doesNotRetryOn4xx() {
        mockToken();
        client.setMaxAttempts(3);
        client.setRetryBackoffMs(1);
        URI uri = firstPage("/threat-detections");
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThrows(org.springframework.web.client.HttpClientErrorException.class,
                () -> client.fetchThreatDetections(1));
        verify(restTemplate, times(1)).exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
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
