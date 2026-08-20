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

    /**
     * Configuration findings are fetched per status, so the status lands in the query string
     * ahead of per-page. Without an explicit status the endpoint silently defaults to FAIL,
     * which is how PASS findings went unsynced.
     */
    private static URI findingsPage(String status) {
        return URI.create("https://api.example/v1/organizations/org_test"
                + "/configuration-findings?status=" + status
                + "&per-page=" + UpwindApiClient.PAGE_SIZE);
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

        when(restTemplate.exchange(eq(findingsPage("FAIL")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(page1);
        when(restTemplate.exchange(eq(URI.create(commaNext)),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(page2);

        UpwindApiClient.FetchResult res = client.fetchConfigurationFindingsPaged("FAIL", 10);
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

        client.fetchConfigurationFindingsPaged("FAIL", 1);

        verify(restTemplate).exchange(eq(findingsPage("FAIL")),
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

        UpwindApiClient.FetchResult res = client.fetchConfigurationFindingsPaged("FAIL", 2);
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

    /**
     * An empty-bodied 400 is not an API verdict.
     *
     * Every 400 Upwind's own validation produces explains itself in the body ("conditions:
     * must not be empty" and friends). One with nothing in it comes from the edge, on the
     * long-lived connections these 285-page walks hold open — and refusing to retry it is
     * what left POSTURE reporting "400 Bad Request: [no body]" for a whole 6-hour cycle.
     */
    @Test
    void retriesAnEmptyBodied400() {
        mockToken();
        client.setMaxAttempts(3);
        client.setRetryBackoffMs(1);
        URI uri = firstPage("/threat-detections");
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.BAD_REQUEST))
                .thenReturn(ResponseEntity.ok("[{\"id\":\"a\"}]"));

        assertEquals(1, client.fetchThreatDetections(1).size());
        verify(restTemplate, times(2)).exchange(eq(uri), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class));
    }

    /** A 400 that explains itself is a real rejection, and retrying it just wastes the window. */
    @Test
    void doesNotRetryA400WithAnExplanation() {
        mockToken();
        client.setMaxAttempts(3);
        client.setRetryBackoffMs(1);
        URI uri = firstPage("/threat-detections");
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Bad Request",
                        new HttpHeaders(), "{\"message\":\"conditions: must not be empty\"}".getBytes(),
                        null));

        assertThrows(org.springframework.web.client.HttpClientErrorException.class,
                () -> client.fetchThreatDetections(1));
        verify(restTemplate, times(1)).exchange(eq(uri), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class));
    }

    /**
     * A failure part-way through a streaming walk keeps the pages already handed to the sink.
     *
     * Those rows are committed as they stream, so unwinding the whole domain over one bad page
     * discarded a mostly-complete refresh and reported it as an outright sync error.
     */
    @Test
    void midWalkFailureKeepsWhatWasStreamedAndNamesThePage() {
        mockToken();
        client.setMaxAttempts(1);
        client.setRetryBackoffMs(1);
        String next = "https://api.example/v1/organizations/org_test/configuration-findings"
                + "?status=FAIL&per-page=1000&page-token=abc";
        HttpHeaders withNext = new HttpHeaders();
        withNext.add("Link", "<" + next + ">; rel=\"next\"");

        when(restTemplate.exchange(eq(findingsPage("FAIL")), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("[{\"id\":\"a\"},{\"id\":\"b\"}]", withNext,
                        org.springframework.http.HttpStatus.OK));
        when(restTemplate.exchange(eq(URI.create(next)), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class)))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.BAD_REQUEST));

        List<JsonNode> seen = new java.util.ArrayList<>();
        UpwindApiClient.StreamResult res =
                client.streamConfigurationFindings("FAIL", 10, seen::add);

        assertEquals(2, seen.size(), "page 1's items must survive page 2's failure");
        assertEquals(2, res.count());
        assertTrue(res.incomplete(), "an aborted walk must never be treated as complete");
        assertFalse(res.truncated(), "it stopped on a failure, not at the page cap");
        assertNotNull(res.failure());
        assertTrue(res.failure().contains("page 2"), res.failure());
        assertTrue(res.failure().contains("configuration-findings?status=FAIL"), res.failure());
        assertTrue(res.failure().contains("empty response body"), res.failure());
    }

    /** Page 1 failing means the request itself was rejected, and that must stay loud. */
    @Test
    void firstPageFailureThrowsWithTheEndpointAndStatusAttached() {
        mockToken();
        client.setMaxAttempts(1);
        client.setRetryBackoffMs(1);
        when(restTemplate.exchange(eq(findingsPage("PASS")), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class)))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.BAD_REQUEST));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.streamConfigurationFindings("PASS", 10, n -> { }));
        assertTrue(e.getMessage().contains("status=PASS"), e.getMessage());
        assertTrue(e.getMessage().contains("page 1"), e.getMessage());
    }

    @Test
    void tokenIsReusedAcrossCalls() {
        mockToken();
        ResponseEntity<String> page = ResponseEntity.ok("[]");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class))).thenReturn(page);

        client.fetchThreatDetections(1);
        client.fetchConfigurationFindingsPaged("FAIL", 1);

        verify(restTemplate, times(1)).exchange(eq("https://oauth.example/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }
}
