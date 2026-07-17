package com.xammer.cloud.service.upwind;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class XamSecureServiceTest {

    private CloudAccountRepository cloudAccountRepo;
    private UpwindThreatDetectionRepository detectionRepo;
    private UpwindVulnerabilityFindingRepository vulnRepo;
    private UpwindPostureFindingRepository postureRepo;
    private UpwindSyncStateRepository syncStateRepo;
    private UpwindAccountConnectionRepository connectionRepo;
    private RedisCacheService redisCacheService;
    private UpwindInventoryAssetRepository inventoryRepo;
    private UpwindApiClient apiClient;
    private XamSecureService service;

    private CloudAccount aws(String awsId) {
        CloudAccount a = new CloudAccount();
        a.setProvider("AWS");
        a.setAwsAccountId(awsId);
        a.setAccountName("acct-" + awsId);
        return a;
    }

    @BeforeEach
    void setUp() {
        cloudAccountRepo = mock(CloudAccountRepository.class);
        detectionRepo = mock(UpwindThreatDetectionRepository.class);
        vulnRepo = mock(UpwindVulnerabilityFindingRepository.class);
        postureRepo = mock(UpwindPostureFindingRepository.class);
        syncStateRepo = mock(UpwindSyncStateRepository.class);
        connectionRepo = mock(UpwindAccountConnectionRepository.class);
        redisCacheService = mock(RedisCacheService.class);
        when(redisCacheService.get(any(String.class), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(Optional.empty());
        inventoryRepo = mock(UpwindInventoryAssetRepository.class);
        apiClient = mock(UpwindApiClient.class);
        service = new XamSecureService(cloudAccountRepo, detectionRepo, vulnRepo,
                postureRepo, syncStateRepo, connectionRepo, redisCacheService, inventoryRepo, apiClient);
    }

    @Test
    void getApiSecurityFetchesPerOwnedAccountAndFlattens() throws Exception {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        com.fasterxml.jackson.databind.JsonNode ep = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree("{\"id\":\"rt-1\",\"method\":\"GET\",\"uri\":\"/version\",\"resource_id\":\"ep-1\"}");
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchApiSecurityEndpoints(eq("111"), anyInt())).thenReturn(List.of(ep));

        var rows = service.getApiSecurity(1L, null);
        assertEquals(1, rows.size());
        assertEquals("GET", rows.get(0).get("method"));
        assertEquals("/version", rows.get(0).get("uri"));
        assertEquals("111", rows.get(0).get("cloudAccountId"));
    }

    @Test
    void resolveAccountIdsReturnsAllOwnedProviderIds() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111"), aws("222")));
        List<String> ids = service.resolveAccountIds(1L, null);
        assertEquals(List.of("111", "222"), ids);
    }

    @Test
    void resolveAccountIdsFiltersToRequestedOwnedAccount() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111"), aws("222")));
        List<String> ids = service.resolveAccountIds(1L, "222");
        assertEquals(List.of("222"), ids);
    }

    @Test
    void resolveAccountIdsRejectsForeignAccount() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        assertThrows(AccessDeniedException.class, () -> service.resolveAccountIds(1L, "999"));
    }

    @Test
    void resolveAccountIdsEmptyWhenClientHasNoAccounts() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of());
        assertTrue(service.resolveAccountIds(1L, null).isEmpty());
    }

    @Test
    void summaryUsesSeverityCountsFromRepos() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(detectionRepo.countOpenBySeverity(List.of("111")))
                .thenReturn(List.<Object[]>of(new Object[]{"HIGH", 3L}));
        when(vulnRepo.countOpenBySeverity(List.of("111")))
                .thenReturn(List.<Object[]>of(new Object[]{"CRITICAL", 5L}));
        when(postureRepo.countFailBySeverity(List.of("111")))
                .thenReturn(List.<Object[]>of(new Object[]{"MEDIUM", 2L}));
        when(syncStateRepo.findAll()).thenReturn(List.of());

        var summary = service.getSummary(1L, null);

        assertEquals(3L, ((java.util.Map<?, ?>) summary.get("threatsBySeverity")).get("HIGH"));
        assertEquals(5L, ((java.util.Map<?, ?>) summary.get("vulnerabilitiesBySeverity")).get("CRITICAL"));
        assertEquals(2L, ((java.util.Map<?, ?>) summary.get("postureBySeverity")).get("MEDIUM"));
    }

    @Test
    void getInventoryFiltersToOwnedAccounts() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        org.springframework.data.domain.Page<com.xammer.cloud.domain.UpwindInventoryAsset> empty =
            org.springframework.data.domain.Page.empty();
        when(inventoryRepo.findByCloudAccountIdInOrderByNameAsc(eq(List.of("111")), any()))
            .thenReturn(empty);
        var page = service.getInventory(1L, null, null, 0, 50);
        assertNotNull(page);
        verify(inventoryRepo).findByCloudAccountIdInOrderByNameAsc(eq(List.of("111")), any());
    }

    @Test
    void getInventoryEmptyWhenNoOwnedAccounts() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of());
        assertTrue(service.getInventory(1L, null, null, 0, 50).isEmpty());
    }

    @Test
    void getIssuesGroupsFailingFindingsByCheckAndCollapsesSeverity() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        java.time.Instant t1 = java.time.Instant.parse("2026-01-01T00:00:00Z");
        java.time.Instant t2 = java.time.Instant.parse("2026-02-01T00:00:00Z");
        // Two resources fail the same check (S3 public) with differing severities + frameworks;
        // one resource fails a second check (MFA).
        // projection: checkTitle, title, severity, frameworkTitle, firstSeen, lastSeen, resourceId
        when(postureRepo.findFailingForIssues(List.of("111"))).thenReturn(List.<Object[]>of(
            new Object[]{"S3 bucket is public", "S3 bucket is public", "MEDIUM", "CIS", t1, t2, "res-1"},
            new Object[]{"S3 bucket is public", "S3 bucket is public", "HIGH", "PCI", t2, t2, "res-2"},
            // same resource repeated via another framework — must NOT inflate the resource count
            new Object[]{"S3 bucket is public", "S3 bucket is public", "HIGH", "SOC2", t2, t2, "res-2"},
            new Object[]{"Root MFA disabled", "Root MFA disabled", "CRITICAL", "CIS", t1, t1, "res-3"}
        ));

        var issues = service.getIssues(1L, null, null);

        assertEquals(2, issues.size());
        // Sorted highest severity first: MFA (CRITICAL) before S3 (HIGH).
        assertEquals("Root MFA disabled", issues.get(0).get("title"));
        assertEquals("CRITICAL", issues.get(0).get("severity"));

        var s3 = issues.get(1);
        assertEquals("S3 bucket is public", s3.get("title"));
        assertEquals("HIGH", s3.get("severity"));           // collapsed to highest of MEDIUM/HIGH
        assertEquals(2, s3.get("resourceCount"));   // res-1, res-2 — the SOC2 duplicate of res-2 is not double-counted
        assertEquals(List.of("CIS", "PCI", "SOC2"), s3.get("frameworks"));
        assertEquals(t1, s3.get("firstSeen"));              // earliest
        assertEquals(t2, s3.get("lastEvaluation"));         // latest
    }

    @Test
    void getIssuesFiltersBySeverity() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        java.time.Instant t = java.time.Instant.parse("2026-01-01T00:00:00Z");
        when(postureRepo.findFailingForIssues(List.of("111"))).thenReturn(List.<Object[]>of(
            new Object[]{"Check A", "Check A", "CRITICAL", "CIS", t, t, "ra"},
            new Object[]{"Check B", "Check B", "LOW", "CIS", t, t, "rb"}
        ));

        var issues = service.getIssues(1L, null, "CRITICAL");
        assertEquals(1, issues.size());
        assertEquals("Check A", issues.get(0).get("title"));
    }

    @Test
    void getIssueDetailParsesDescriptionAndRemediationAndResources() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        com.xammer.cloud.domain.UpwindPostureFinding f = new com.xammer.cloud.domain.UpwindPostureFinding();
        f.setSeverity("HIGH");
        f.setFrameworkTitle("CIS");
        f.setResourceId("arn:bucket-1");
        f.setResourceName("bucket-1");
        f.setResourceType("s3");
        f.setCloudProvider("AWS");
        f.setFirstSeenTime(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        f.setLastSeenTime(java.time.Instant.parse("2026-02-01T00:00:00Z"));
        f.setRawPayload("{\"check\":{\"description\":\"Bucket is public\",\"remediation\":\"Make it private\"}}");
        when(postureRepo.findFailingByCheck(List.of("111"), "S3 bucket is public"))
            .thenReturn(List.of(f));

        var detail = service.getIssueDetail(1L, null, "S3 bucket is public");

        assertEquals("Bucket is public", detail.get("description"));
        assertEquals("Make it private", detail.get("remediation"));
        assertEquals("HIGH", detail.get("severity"));
        assertEquals(1, detail.get("resourceCount"));
        assertEquals(List.of("CIS"), detail.get("frameworks"));
        @SuppressWarnings("unchecked")
        var resources = (List<java.util.Map<String, Object>>) detail.get("resources");
        assertEquals("bucket-1", resources.get(0).get("resourceName"));
    }

    @Test
    void getThreatStoriesFiltersForeignAccountsAndCountsActive() throws Exception {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(apiClient.isConfigured()).thenReturn(true);
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var owned = om.readTree("{\"id\":\"s1\",\"title\":\"Story 1\",\"severity\":\"HIGH\",\"status\":\"OPEN\",\"resource\":{\"cloud_account_id\":\"111\",\"name\":\"res-1\"}}");
        var foreign = om.readTree("{\"id\":\"s2\",\"status\":\"OPEN\",\"resource\":{\"cloud_account_id\":\"999\"}}");
        var resolved = om.readTree("{\"id\":\"s3\",\"status\":\"RESOLVED\",\"resource\":{\"cloud_account_id\":\"111\"}}");
        when(apiClient.searchThreatStories(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(owned, foreign, resolved));

        var summary = service.getThreatStories(1L, null);

        assertEquals(1, summary.get("activeCount"));        // only the OPEN owned story
        @SuppressWarnings("unchecked")
        var stories = (List<java.util.Map<String, Object>>) summary.get("stories");
        assertEquals(2, stories.size());                    // both owned stories, foreign excluded
        assertEquals("s1", stories.get(0).get("id"));
    }

    @Test
    void getThreatStoriesEmptyWhenUpwindNotConfigured() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(apiClient.isConfigured()).thenReturn(false);
        var summary = service.getThreatStories(1L, null);
        assertEquals(0, summary.get("activeCount"));
        verify(apiClient, never()).searchThreatStories(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void getThreatStoriesDegradesGracefullyOnUpwindError() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchThreatStories(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("upwind down"));
        var summary = service.getThreatStories(1L, null);
        assertEquals(0, summary.get("activeCount"));
        assertTrue(((List<?>) summary.get("stories")).isEmpty());
    }

    @Test
    void getAttackSurfaceGroupsExposureFindingsAndCountsResources() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        java.sql.Timestamp t = java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        // columns: checkTitle, title, severity, resourceId, resourceName, resourceType, first, last, framework, region
        when(postureRepo.findExposureFailings(List.of("111"))).thenReturn(List.<Object[]>of(
            new Object[]{"S3 Bucket is publicly accessible with anonymous read access", "S3 Bucket is publicly accessible with anonymous read access", "HIGH", "arn:s3:b1", "b1", "AWS_S3_BUCKET", t, t, "CIS", "ap-south-1"},
            new Object[]{"S3 Bucket is publicly accessible with anonymous read access", "S3 Bucket is publicly accessible with anonymous read access", "HIGH", "arn:s3:b2", "b2", "AWS_S3_BUCKET", t, t, "CIS", "us-east-1"},
            new Object[]{"AWS EC2 instances have public IP addresses", "AWS EC2 instances have public IP addresses", "CRITICAL", "i-1", "i-1", "AWS_EC2_INSTANCE", t, t, "CIS", "ap-south-1"},
            // network plumbing — excluded by resource type
            new Object[]{"Security groups allow unrestricted RDP access from 0.0.0.0/0", "Security groups allow unrestricted RDP access from 0.0.0.0/0", "HIGH", "sg-1", "sg-1", "AWS_EC2_SECURITY_GROUP", t, t, "CIS", "ap-south-1"},
            // general posture (not external exposure) — excluded by impact classifier
            new Object[]{"EC2 instances do not enforce IMDSv2", "EC2 instances do not enforce IMDSv2", "MEDIUM", "i-9", "i-9", "AWS_EC2_INSTANCE", t, t, "CIS", "ap-south-1"}
        ));

        var as = service.getAttackSurface(1L, null);

        assertEquals(3, as.get("exposedResourceCount"));   // b1, b2, i-1 (sg-1 and i-9 excluded)
        @SuppressWarnings("unchecked")
        var findings = (List<java.util.Map<String, Object>>) as.get("findings");
        assertEquals(2, findings.size());  // S3 (Authenticated) + EC2 public IP (Public); IMDSv2/SG dropped
        assertEquals("AWS EC2 instances have public IP addresses", findings.get(0).get("title")); // CRITICAL first
        assertEquals("Public exposure", findings.get(0).get("impact"));
        assertEquals("Authenticated exposure", findings.get(1).get("impact"));
        @SuppressWarnings("unchecked")
        var byType = (List<java.util.Map<String, Object>>) as.get("exposedByType");
        assertEquals("AWS_S3_BUCKET", byType.get(0).get("type"));  // 2 distinct resources → top
        assertEquals(2, byType.get(0).get("count"));
        // security group excluded → only the 2 workload findings remain
        assertEquals(2, findings.size());
        // location breakdown: India (b1, i-1) and United States (b2)
        @SuppressWarnings("unchecked")
        var byLoc = (List<java.util.Map<String, Object>>) as.get("exposedByLocation");
        assertEquals("India", byLoc.get(0).get("country"));
        assertEquals(2, byLoc.get(0).get("count"));
    }

    @Test
    void getAttackSurfaceEmptyWhenNoOwnedAccounts() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of());
        var as = service.getAttackSurface(1L, null);
        assertEquals(0, as.get("exposedResourceCount"));
    }

    @Test
    void getIssuesEmptyWhenNoOwnedAccounts() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of());
        assertTrue(service.getIssues(1L, null, null).isEmpty());
    }

    @Test
    void updateThreatStatusRejectsForeignDetection() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        com.xammer.cloud.domain.UpwindThreatDetection d = new com.xammer.cloud.domain.UpwindThreatDetection();
        d.setUpwindId("uwnd-1"); d.setCloudAccountId("999"); // not owned
        when(detectionRepo.findByUpwindId("uwnd-1")).thenReturn(Optional.of(d));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> service.updateThreatStatus(1L, "uwnd-1", "ARCHIVED"));
        verify(apiClient, never()).updateDetectionStatus(any(), any());
    }

    @Test
    void updateThreatStatusPatchesAndPersists() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        com.xammer.cloud.domain.UpwindThreatDetection d = new com.xammer.cloud.domain.UpwindThreatDetection();
        d.setUpwindId("uwnd-1"); d.setCloudAccountId("111");
        when(detectionRepo.findByUpwindId("uwnd-1")).thenReturn(Optional.of(d));

        service.updateThreatStatus(1L, "uwnd-1", "ARCHIVED");

        verify(apiClient).updateDetectionStatus("uwnd-1", "ARCHIVED");
        org.mockito.ArgumentCaptor<com.xammer.cloud.domain.UpwindThreatDetection> cap =
            org.mockito.ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindThreatDetection.class);
        verify(detectionRepo).save(cap.capture());
        assertEquals("ARCHIVED", cap.getValue().getStatus());
    }
}
