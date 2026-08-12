package com.xammer.cloud.service.upwind;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
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
    private UpwindApiEndpointRepository apiEndpointRepo;
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
        apiEndpointRepo = mock(UpwindApiEndpointRepository.class);
        apiClient = mock(UpwindApiClient.class);
        service = new XamSecureService(cloudAccountRepo, detectionRepo, vulnRepo,
                postureRepo, syncStateRepo, connectionRepo, redisCacheService, inventoryRepo,
                apiEndpointRepo, apiClient);
    }

    @Test
    void identitiesGroupChecksPerIdentityAndKeepWorstSeverity() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        java.time.Instant t1 = java.time.Instant.parse("2026-07-01T00:00:00Z");
        java.time.Instant t2 = java.time.Instant.parse("2026-07-20T00:00:00Z");
        // Two findings on the same IAM user, plus one on a role.
        when(postureRepo.findIdentityFailings(List.of("111"))).thenReturn(List.of(
                new Object[]{"arn:iam::user/kartik", "kartik", "AWS_IAM_USER", "MFA should be enabled",
                        null, "MEDIUM", "AWS", "111", t2, t1},
                new Object[]{"arn:iam::user/kartik", "kartik", "AWS_IAM_USER", "Access keys not rotated",
                        null, "HIGH", "AWS", "111", t2, t1},
                new Object[]{"arn:iam::role/deploy", "deploy", "AWS_IAM_ROLE", "Role assumable by other accounts",
                        null, "HIGH", "AWS", "111", t2, t1}));

        var out = service.getIdentities(1L, null);

        assertEquals(2, out.get("total"));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) out.get("identities");
        var kartik = rows.stream().filter(r -> "kartik".equals(r.get("name"))).findFirst().orElseThrow();
        // Both checks collapse onto one identity, and the worst severity wins.
        assertEquals(2, kartik.get("findingCount"));
        assertEquals("HIGH", kartik.get("severity"));
        @SuppressWarnings("unchecked")
        var counts = (Map<String, Long>) out.get("severityCounts");
        assertEquals(2L, counts.get("HIGH"));
        assertNull(counts.get("MEDIUM"), "MEDIUM was superseded on that identity");
        @SuppressWarnings("unchecked")
        var byType = (List<Map<String, Object>>) out.get("byType");
        assertEquals(2, byType.size());
    }

    /**
     * The roster is the inventory catalog's identity assets, not just the subjects of failing
     * checks. A clean identity must still be listed — that is the difference between an
     * inventory and a findings list.
     */
    @Test
    void identitiesRosterComesFromInventoryIncludingCleanIdentities() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        var deploy = identityAsset("arn:aws:iam::111:role/deploy", "deploy", "aws_iam_role", true);
        when(inventoryRepo.findIdentityAssets(List.of("111")))
                .thenReturn(List.of(
                        deploy,
                        identityAsset("arn:aws:iam::111:user/clean", "clean", "aws_iam_user", false),
                        identityAsset("k8s/sa/builder", "builder", "kubernetes_service_account", false)));
        // Privilege list is sourced separately, so it can span non-identity assets too.
        when(inventoryRepo.findElevatedPrivilegeAssets(List.of("111"))).thenReturn(List.of(deploy));
        when(postureRepo.findFailingByResourceIds(eq(List.of("111")), anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{
                        "arn:aws:iam::111:role/deploy", "Role assumable by other accounts", null,
                        "HIGH", java.time.Instant.parse("2026-08-01T00:00:00Z"),
                        java.time.Instant.parse("2026-07-01T00:00:00Z")}));

        var out = service.getIdentities(1L, null);

        assertEquals(3, out.get("total"), "clean identities are part of the roster");
        assertEquals(1, out.get("withFindings"));
        assertEquals(1, out.get("privilegedTotal"));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) out.get("identities");
        // Worst severity leads.
        assertEquals("deploy", rows.get(0).get("name"));
        assertEquals("HIGH", rows.get(0).get("severity"));
        assertEquals(1, rows.get(0).get("findingCount"));
        assertEquals(true, rows.get(0).get("elevatedPrivilege"));
        // A clean identity is present with no severity and no findings.
        var clean = rows.stream().filter(r -> "clean".equals(r.get("name"))).findFirst().orElseThrow();
        assertNull(clean.get("severity"));
        assertEquals(0, clean.get("findingCount"));
        // Kubernetes identities are included, which the old resource-type LIKE guess missed.
        assertTrue(rows.stream().anyMatch(r -> "kubernetes_service_account".equals(r.get("resourceType"))));
    }

    /** An identity failing a check but missing from the last inventory walk must not vanish. */
    @Test
    void identitiesKeepFindingOnlyOrphansNotInInventory() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(inventoryRepo.findIdentityAssets(List.of("111"))).thenReturn(List.of());
        java.time.Instant t = java.time.Instant.parse("2026-07-01T00:00:00Z");
        when(postureRepo.findIdentityFailings(List.of("111"))).thenReturn(List.<Object[]>of(
                new Object[]{"arn:aws:iam::111:user/ghost", "ghost", "AWS_IAM_USER",
                        "MFA should be enabled", null, "MEDIUM", "AWS", "111", t, t}));

        var out = service.getIdentities(1L, null);

        assertEquals(1, out.get("total"));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) out.get("identities");
        assertEquals("ghost", rows.get(0).get("name"));
        assertEquals(false, rows.get(0).get("inInventory"));
    }

    private static com.xammer.cloud.domain.UpwindInventoryAsset identityAsset(
            String resourceId, String name, String label, boolean privileged) {
        var a = new com.xammer.cloud.domain.UpwindInventoryAsset();
        a.setCloudResourceId(resourceId);
        a.setName(name);
        a.setLabel(label);
        a.setAssetCategory("identity");
        a.setCloudProvider("AWS");
        a.setCloudAccountId("111");
        a.setHasElevatedPrivilege(privileged);
        return a;
    }

    /**
     * high_privilege_risk.has_elevated_privilege comes from the inventory catalog, so
     * Identities can now show privilege both as its own roster and as a per-row flag —
     * previously the view had to state that privilege data was unobtainable.
     */
    @Test
    void identitiesJoinElevatedPrivilegeFromInventoryCatalog() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        java.time.Instant t = java.time.Instant.parse("2026-07-01T00:00:00Z");
        when(postureRepo.findIdentityFailings(List.of("111"))).thenReturn(List.<Object[]>of(
                new Object[]{"arn:iam::role/deploy", "deploy", "AWS_IAM_ROLE", "Role assumable",
                        null, "HIGH", "AWS", "111", t, t}));

        com.xammer.cloud.domain.UpwindInventoryAsset privileged =
                new com.xammer.cloud.domain.UpwindInventoryAsset();
        privileged.setCloudResourceId("arn:iam::role/deploy");
        privileged.setName("deploy");
        privileged.setLabel("aws_iam_role");
        privileged.setHasElevatedPrivilege(true);
        com.xammer.cloud.domain.UpwindInventoryAsset cleanButPrivileged =
                new com.xammer.cloud.domain.UpwindInventoryAsset();
        cleanButPrivileged.setCloudResourceId("arn:iam::role/admin");
        cleanButPrivileged.setName("admin");
        cleanButPrivileged.setHasElevatedPrivilege(true);
        when(inventoryRepo.findElevatedPrivilegeAssets(List.of("111")))
                .thenReturn(List.of(privileged, cleanButPrivileged));

        var out = service.getIdentities(1L, null);

        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) out.get("identities");
        assertEquals(true, rows.get(0).get("elevatedPrivilege"));
        // The privileged roster is not an intersection: a privileged asset with no failing
        // check is exactly the case worth surfacing, so it must still be listed.
        assertEquals(2, out.get("privilegedTotal"));
        @SuppressWarnings("unchecked")
        var priv = (List<Map<String, Object>>) out.get("privileged");
        var admin = priv.stream().filter(p -> "admin".equals(p.get("name"))).findFirst().orElseThrow();
        assertEquals(false, admin.get("hasFindings"));
    }

    /**
     * "Internet exposure" and "Functions in Use" were hardcoded to unavailable because the
     * sync discarded the fields behind them. Both now come from promoted columns.
     */
    @Test
    void vulnDashboardFunnelSourcesRuntimeExposureStages() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(vulnRepo.countCvesPackageInUse(List.of("111"))).thenReturn(930L);
        when(vulnRepo.countCvesFixAvailable(List.of("111"))).thenReturn(400L);
        when(vulnRepo.countRuntimeExposure(List.of("111")))
                .thenReturn(List.<Object[]>of(new Object[]{379L, 364L, 5L, 162L}));

        var out = service.getVulnerabilityDashboard(1L, null);

        @SuppressWarnings("unchecked")
        var funnel = (List<Map<String, Object>>) out.get("funnel");
        var exposure = funnel.stream()
                .filter(s -> "Internet exposure".equals(s.get("label"))).findFirst().orElseThrow();
        assertEquals(379, exposure.get("value"));
        assertEquals(true, exposure.get("available"), "no longer a hardcoded zero");
        var fns = funnel.stream()
                .filter(s -> "Functions in Use".equals(s.get("label"))).findFirst().orElseThrow();
        assertEquals(5, fns.get("value"));
        assertEquals(true, fns.get("available"));
        // NIST critical severity still has no honest source, so it stays marked unavailable.
        var nist = funnel.stream()
                .filter(s -> "NIST critical sev".equals(s.get("label"))).findFirst().orElseThrow();
        assertEquals(false, nist.get("available"));

        @SuppressWarnings("unchecked")
        var runtime = (Map<String, Object>) out.get("runtimeExposure");
        assertEquals(379L, runtime.get("internetExposed"));
        assertEquals(364L, runtime.get("exposedAndInUse"));
        assertEquals(162L, runtime.get("activeCommunication"));
        assertEquals(5L, runtime.get("functionsInUse"));
    }

    /** No rows yet must read as zeroes, not blow up on a null aggregate row. */
    @Test
    void vulnDashboardRuntimeExposureToleratesNoData() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(vulnRepo.countRuntimeExposure(List.of("111")))
                .thenReturn(List.<Object[]>of(new Object[]{null, null, null, null}));

        var out = service.getVulnerabilityDashboard(1L, null);

        @SuppressWarnings("unchecked")
        var runtime = (Map<String, Object>) out.get("runtimeExposure");
        assertEquals(0L, runtime.get("internetExposed"));
        assertEquals(0L, runtime.get("functionsInUse"));
    }

    // --- Threat story detail ---

    private static com.fasterxml.jackson.databind.JsonNode storyJson() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"id\":\"uwis-1\",\"title\":\"Cryptominer Staging Across Kubernetes Workloads\","
                + "\"severity\":\"critical\",\"status\":\"open\","
                + "\"create_time\":\"2026-08-02T16:14:36Z\",\"update_time\":\"2026-08-02T19:44:06Z\","
                + "\"description\":\"# Incident Summary\\nXMRig was downloaded.\","
                + "\"detection_ids\":[\"uwd-a\",\"uwd-b\",\"uwd-c\"]}");
    }

    private static com.xammer.cloud.domain.UpwindThreatDetection detection(
            String id, String severity, String resourceId, String tactics) {
        var d = new com.xammer.cloud.domain.UpwindThreatDetection();
        d.setUpwindId(id);
        d.setTitle("Detection " + id);
        d.setSeverity(severity);
        d.setStatus("OPEN");
        d.setCategory("Runtime");
        d.setResourceId(resourceId);
        d.setResourceName(resourceId);
        d.setResourceType("KUBERNETES_DEPLOYMENT");
        d.setCloudProvider("AWS");
        d.setCloudAccountId("111");
        d.setMitreTactics(tactics);
        d.setOccurrenceCount(3);
        d.setFirstSeenTime(java.time.Instant.parse("2026-08-02T15:00:00Z"));
        d.setLastSeenTime(java.time.Instant.parse("2026-08-02T19:00:00Z"));
        return d;
    }

    @Test
    void storyDetailAggregatesDetectionsMitreAndResources() throws Exception {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.getThreatStory("uwis-1")).thenReturn(storyJson());
        when(detectionRepo.findByAccountsAndUpwindIds(eq(List.of("111")), anyList()))
                .thenReturn(List.of(
                        detection("uwd-a", "CRITICAL", "res-1", "Execution,Discovery"),
                        detection("uwd-b", "HIGH", "res-1", "Execution"),
                        detection("uwd-c", "MEDIUM", "res-2", "Discovery")));

        var out = service.getThreatStoryDetail(1L, null, "uwis-1");

        assertEquals("uwis-1", out.get("id"));
        // Upwind lowercases severity/status; we normalise so the chips match every other tab.
        assertEquals("CRITICAL", out.get("severity"));
        assertEquals("OPEN", out.get("status"));
        assertEquals("CRITICAL", out.get("worstSeverity"));
        assertEquals(3, out.get("detectionCount"));
        assertEquals(3, out.get("resolvedCount"));
        assertTrue(((String) out.get("description")).contains("Incident Summary"));

        @SuppressWarnings("unchecked")
        var tactics = (List<Map<String, Object>>) out.get("mitreTactics");
        // Execution appears twice, Discovery twice — most frequent first, deduped per tactic.
        assertEquals(2, tactics.size());
        assertEquals(2L, tactics.get(0).get("count"));

        @SuppressWarnings("unchecked")
        var resources = (List<Map<String, Object>>) out.get("resources");
        assertEquals(2, resources.size(), "two distinct resources across three detections");
        var res1 = resources.stream()
                .filter(r -> "res-1".equals(r.get("resourceId"))).findFirst().orElseThrow();
        assertEquals(2L, res1.get("detectionCount"));
    }

    /**
     * Tenancy. Stories carry no cloud_account_id — the payload has only id/title/severity/
     * status/timestamps/detection_ids — so the old story.resource.cloud_account_id check
     * never matched and every client could see every story in the org. Ownership now comes
     * from the detections, and a story with none of ours must be withheld entirely.
     */
    @Test
    void storyDetailIsWithheldWhenNoDetectionBelongsToTheClient() throws Exception {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.getThreatStory("uwis-1")).thenReturn(storyJson());
        when(detectionRepo.findByAccountsAndUpwindIds(eq(List.of("111")), anyList()))
                .thenReturn(List.of());

        var out = service.getThreatStoryDetail(1L, null, "uwis-1");

        assertTrue(out.isEmpty(), "a story we own no detection in must not be returned at all");
    }

    /** A story spanning accounts we cannot see reports the shortfall rather than hiding it. */
    @Test
    void storyDetailReportsPartiallyResolvedDetections() throws Exception {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.getThreatStory("uwis-1")).thenReturn(storyJson());
        when(detectionRepo.findByAccountsAndUpwindIds(eq(List.of("111")), anyList()))
                .thenReturn(List.of(detection("uwd-a", "HIGH", "res-1", "Execution")));

        var out = service.getThreatStoryDetail(1L, null, "uwis-1");

        assertEquals(3, out.get("detectionCount"), "what the story references");
        assertEquals(1, out.get("resolvedCount"), "what we can actually show");
    }

    /** An upstream failure must degrade to empty, not propagate out of the controller. */
    @Test
    void storyDetailReturnsEmptyWhenUpwindFails() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.getThreatStory("uwis-1")).thenThrow(new IllegalStateException("502"));

        assertTrue(service.getThreatStoryDetail(1L, null, "uwis-1").isEmpty());
    }

    @Test
    void inventoryRiskRollupMapsClassificationCounts() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(inventoryRepo.riskRollup(List.of("111"))).thenReturn(List.<Object[]>of(
                new Object[]{2058L, 31L, 0L, 4L, 12L, 7L, 96L}));

        var out = service.getInventoryRisk(1L, null);

        assertEquals(2058L, out.get("assets"));
        assertEquals(31L, out.get("pii"));
        assertEquals(0L, out.get("phi"));
        assertEquals(12L, out.get("elevatedPrivilege"));
        assertEquals(7L, out.get("eolTechnology"));
        assertEquals(96L, out.get("internetExposed"));
    }

    /** No synced assets yet must read as zeroes, not as a null-laden payload. */
    @Test
    void inventoryRiskRollupDefaultsToZeroWhenNothingSynced() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(inventoryRepo.riskRollup(List.of("111"))).thenReturn(List.<Object[]>of(
                new Object[]{0L, null, null, null, null, null, null}));

        var out = service.getInventoryRisk(1L, null);

        assertEquals(0L, out.get("assets"));
        assertEquals(0L, out.get("pii"));
        assertEquals(0L, out.get("elevatedPrivilege"));
    }

    /** v2-only endpoint fields must reach the read model the APIs tab consumes. */
    @Test
    void apiSecurityRowsExposeSensitiveDataAndStatusCodes() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        com.xammer.cloud.domain.UpwindApiEndpoint e = new com.xammer.cloud.domain.UpwindApiEndpoint();
        e.setUpwindId("rt-1");
        e.setCloudAccountId("111");
        e.setMethod("GET");
        e.setUri("/api/profile");
        e.setSensitiveData("PII:Email,PII:Phone");
        e.setHasSensitiveData(true);
        e.setStatusCodes("200,401");
        when(apiEndpointRepo.findByCloudAccountIdInAndStatusOrderByUriAsc(List.of("111"), "ACTIVE"))
                .thenReturn(List.of(e));

        var rows = service.getApiSecurity(1L, null);

        assertEquals(List.of("PII:Email", "PII:Phone"), rows.get(0).get("sensitiveData"));
        assertEquals(true, rows.get(0).get("hasSensitiveData"));
        assertEquals(List.of("200", "401"), rows.get(0).get("statusCodes"));
    }

    @Test
    void syncStatusFlagsStaleAndFailingDomains() {
        com.xammer.cloud.domain.UpwindSyncState fresh = new com.xammer.cloud.domain.UpwindSyncState();
        fresh.setDomain("DETECTIONS");
        fresh.setLastSuccessAt(java.time.Instant.now());
        when(syncStateRepo.findByDomain("DETECTIONS")).thenReturn(Optional.of(fresh));

        com.xammer.cloud.domain.UpwindSyncState broken = new com.xammer.cloud.domain.UpwindSyncState();
        broken.setDomain("POSTURE");
        broken.setLastSuccessAt(java.time.Instant.now().minusSeconds(86_400));
        broken.setLastErrorAt(java.time.Instant.now());
        broken.setLastError("connection reset");
        when(syncStateRepo.findByDomain("POSTURE")).thenReturn(Optional.of(broken));
        when(syncStateRepo.findByDomain("VULNERABILITIES")).thenReturn(Optional.empty());
        when(syncStateRepo.findByDomain("INVENTORY")).thenReturn(Optional.empty());
        when(syncStateRepo.findByDomain("API_ENDPOINTS")).thenReturn(Optional.empty());

        var status = service.getSyncStatus();

        assertEquals("ERROR", status.get("status"));
        @SuppressWarnings("unchecked")
        var domains = (List<Map<String, Object>>) status.get("domains");
        assertEquals(5, domains.size());
        var detections = domains.stream().filter(d -> "DETECTIONS".equals(d.get("domain"))).findFirst().orElseThrow();
        assertEquals(false, detections.get("stale"));
        assertEquals(false, detections.get("failing"));
        var posture = domains.stream().filter(d -> "POSTURE".equals(d.get("domain"))).findFirst().orElseThrow();
        assertEquals(true, posture.get("stale"));
        assertEquals(true, posture.get("failing"));
        assertEquals("connection reset", posture.get("lastError"));
        // Never synced at all must read as stale, not as healthy.
        var never = domains.stream().filter(d -> "INVENTORY".equals(d.get("domain"))).findFirst().orElseThrow();
        assertEquals(true, never.get("stale"));
        assertNull(never.get("lastSuccessAt"));
    }

    @Test
    void getApiSecurityReadsSyncedEndpointsWithoutCallingUpwind() {
        // The tab used to walk ~6.8k endpoints inline (~20s). It now reads the synced table.
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        com.xammer.cloud.domain.UpwindApiEndpoint e = new com.xammer.cloud.domain.UpwindApiEndpoint();
        e.setUpwindId("rt-1");
        e.setCloudAccountId("111");
        e.setMethod("GET");
        e.setUri("/version");
        e.setInternetExposed(true);
        e.setAuthState("UNAUTHENTICATED");
        e.setDomains("a.example,b.example");
        when(apiEndpointRepo.findByCloudAccountIdInAndStatusOrderByUriAsc(List.of("111"), "ACTIVE"))
                .thenReturn(List.of(e));

        var rows = service.getApiSecurity(1L, null);

        assertEquals(1, rows.size());
        assertEquals("/version", rows.get(0).get("uri"));
        assertEquals(true, rows.get(0).get("internetExposed"));
        assertEquals(false, rows.get(0).get("authenticated"));
        assertEquals(List.of("a.example", "b.example"), rows.get(0).get("domains"));
        verify(apiClient, never()).searchApiEndpoints(any(), anyInt());
    }

    @Test
    void getApiSecurityFetchesPerOwnedAccountAndFlattens() throws Exception {
        // Fallback path: nothing synced yet, so the live read-through still serves the tab.
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        com.fasterxml.jackson.databind.JsonNode ep = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree("{\"id\":\"rt-1\",\"method\":\"GET\",\"uri\":\"/version\",\"resource_id\":\"ep-1\"}");
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchApiEndpoints(eq("111"), anyInt()))
                .thenReturn(new UpwindApiClient.FetchResult(List.of(ep), false));

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
    void resolveAccountIdsNarrowsToASingleSelectedAccount() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111"), aws("222")));
        assertEquals(List.of("222"), service.resolveAccountIds(1L, "222"));
    }

    /**
     * The picker supports multi-select. Before this, only the first id was sent, so choosing
     * two accounts showed ONE account's data — less than choosing none.
     */
    @Test
    void resolveAccountIdsAcceptsAMultiAccountSelection() {
        when(cloudAccountRepo.findByClientId(1L))
                .thenReturn(List.of(aws("111"), aws("222"), aws("333")));
        assertEquals(List.of("111", "333"), service.resolveAccountIds(1L, "111,333"));
        assertEquals(List.of("111", "222"), service.resolveAccountIds(1L, " 111 , 222 "));
    }

    /** Tenancy: an unowned account must be refused even when bundled with owned ones. */
    @Test
    void resolveAccountIdsRejectsAnyUnownedAccountInTheSelection() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.resolveAccountIds(1L, "111,999"));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.resolveAccountIds(1L, "999"));
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
        // findActiveByAccounts, not the old unfiltered derived method: the table has to agree
        // with the category rail and the Assets tile, which both count ACTIVE only.
        when(inventoryRepo.findActiveByAccounts(eq(List.of("111")), any()))
            .thenReturn(empty);
        var page = service.getInventory(1L, null, null, 0, 50);
        assertNotNull(page);
        verify(inventoryRepo).findActiveByAccounts(eq(List.of("111")), any());
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

    /**
     * Story tenancy comes from correlated detections, not from a field on the story.
     *
     * The previous version of this test gave each story a resource.cloud_account_id and
     * asserted the service filtered on it. Real stories carry no account id at all (verified
     * live 2026-08-04: the payload is id/title/severity/status/create_time/update_time/
     * detection_ids), so that fixture made a broken filter look correct and every org story
     * reached every client. These fixtures now match the real shape.
     */
    @Test
    void getThreatStoriesEstablishesTenancyFromCorrelatedDetections() throws Exception {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(apiClient.isConfigured()).thenReturn(true);
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var ours = om.readTree("{\"id\":\"s1\",\"title\":\"Story 1\",\"severity\":\"HIGH\","
                + "\"status\":\"OPEN\",\"detection_ids\":[\"uwd-a\",\"uwd-b\"]}");
        var foreign = om.readTree("{\"id\":\"s2\",\"status\":\"OPEN\","
                + "\"detection_ids\":[\"uwd-z\"]}");
        var resolvedStory = om.readTree("{\"id\":\"s3\",\"status\":\"RESOLVED\","
                + "\"detection_ids\":[\"uwd-a\"]}");
        when(apiClient.searchThreatStories(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(ours, foreign, resolvedStory));
        // Only uwd-a and uwd-b are in account 111; uwd-z belongs to somebody else.
        when(detectionRepo.findByAccountsAndUpwindIds(eq(List.of("111")), anyList()))
                .thenReturn(List.of(
                        detection("uwd-a", "CRITICAL", "res-1", "Execution"),
                        detection("uwd-b", "HIGH", "res-1", "Execution")));

        var summary = service.getThreatStories(1L, null);

        assertEquals(1, summary.get("activeCount"));        // only the OPEN story we own
        @SuppressWarnings("unchecked")
        var stories = (List<java.util.Map<String, Object>>) summary.get("stories");
        assertEquals(2, stories.size());                    // s1 and s3; s2 correlates nothing of ours
        assertEquals("s1", stories.get(0).get("id"));
        assertEquals(2, stories.get(0).get("relatedEvents")); // what the story references
        assertEquals(2L, stories.get(0).get("resolvedCount")); // how much of it we hold
        assertTrue(stories.stream().noneMatch(m -> "s2".equals(m.get("id"))));
    }

    /** A story naming only other tenants' detections must not appear at all. */
    @Test
    void getThreatStoriesWithholdsStoriesCorrelatingNoneOfOurDetections() throws Exception {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(apiClient.isConfigured()).thenReturn(true);
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var foreign = om.readTree("{\"id\":\"s9\",\"title\":\"Cryptominer in another tenant\","
                + "\"status\":\"OPEN\",\"detection_ids\":[\"uwd-x\",\"uwd-y\"]}");
        when(apiClient.searchThreatStories(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(foreign));
        when(detectionRepo.findByAccountsAndUpwindIds(eq(List.of("111")), anyList()))
                .thenReturn(List.of());

        var summary = service.getThreatStories(1L, null);

        assertEquals(0, summary.get("activeCount"));
        @SuppressWarnings("unchecked")
        var stories = (List<java.util.Map<String, Object>>) summary.get("stories");
        assertTrue(stories.isEmpty());
    }

    @Test
    void threatOverviewAggregatesTacticsSourcesAndDenseTrend() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(apiClient.isConfigured()).thenReturn(false);   // stories unavailable; aggregates still work
        var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.Instant onDay = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                .plusSeconds(3600);
        java.time.Instant tooOld = onDay.minusSeconds(60L * 60 * 24 * 30);
        when(detectionRepo.findDashboardFacets(List.of("111"))).thenReturn(List.of(
                new Object[]{"CRITICAL", "Execution,Discovery", "CLOUD_TRAIL", onDay},
                new Object[]{"HIGH", "Execution", "SENSOR", onDay},
                new Object[]{"MEDIUM", null, "weird_source", onDay},
                // Outside the seven-day window: counted in the facets, absent from the trend.
                new Object[]{"LOW", "Discovery", "CLOUD_TRAIL", tooOld}));
        when(detectionRepo.countDistinctResources(List.of("111"))).thenReturn(4L);

        var out = service.getThreatOverview(1L, null);

        assertEquals(4, out.get("detectionCount"));
        assertEquals(4L, out.get("resourceCount"));
        // The funnel's event stage has no equivalent on our side and must not be invented.
        assertEquals(false, out.get("eventCountAvailable"));
        assertNull(out.get("eventCount"));

        @SuppressWarnings("unchecked")
        var tactics = (List<java.util.Map<String, Object>>) out.get("byTactic");
        // A detection with several tactics counts under each, as Upwind does it: Execution from
        // rows 1-2, Discovery from rows 1 and 4. Equal counts tie-break alphabetically so the
        // chart ordering is stable between requests rather than dependent on map iteration.
        assertEquals("Discovery", tactics.get(0).get("tactic"));
        assertEquals(2L, tactics.get(0).get("count"));
        assertEquals("Execution", tactics.get(1).get("tactic"));
        assertEquals(2L, tactics.get(1).get("count"));

        @SuppressWarnings("unchecked")
        var sources = (List<java.util.Map<String, Object>>) out.get("bySource");
        var labels = sources.stream().map(m -> (String) m.get("source")).toList();
        assertTrue(labels.contains("AWS CloudTrail"));       // curated label
        assertTrue(labels.contains("Weird Source"));         // fallback prettifier

        @SuppressWarnings("unchecked")
        var trend = (List<java.util.Map<String, Object>>) out.get("trend");
        assertEquals(7, trend.size());                       // dense: quiet days appear as zero
        var last = trend.get(6);
        assertEquals(today.toString(), last.get("date"));
        assertEquals(1L, last.get("critical"));
        assertEquals(1L, last.get("high"));
        assertEquals(1L, last.get("other"));                 // MEDIUM banded into "other"
        assertEquals(0L, trend.get(0).get("critical"));
    }

    /** Projection row for findAllForCveGrouping (14 columns; last three are runtime flags). */
    private static Object[] cveRow(String cveId, String severity, String resourceId,
                                   Boolean inUse, Boolean exposed, Boolean functionsInUse) {
        return new Object[]{cveId, cveId + " name", severity, "9.8", Boolean.TRUE, "0.5",
                resourceId, resourceId, "KUBERNETES_DEPLOYMENT",
                java.time.Instant.parse("2026-08-01T00:00:00Z"),
                java.time.Instant.parse("2026-08-05T00:00:00Z"),
                inUse, exposed, functionsInUse};
    }

    @Test
    void cveViewRuntimeKeepsOnlyReachableCvesAndReportsBothTotals() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(vulnRepo.findAllForCveGrouping(List.of("111"))).thenReturn(List.of(
                // Reachable: loaded and behind ingress.
                cveRow("CVE-1", "CRITICAL", "res-a", true, true, false),
                // Loaded but not exposed.
                cveRow("CVE-2", "CRITICAL", "res-b", true, false, false),
                // Exposed but never loaded.
                cveRow("CVE-3", "HIGH", "res-c", false, true, false),
                // Neither.
                cveRow("CVE-4", "LOW", "res-d", false, false, false)));

        var all = service.getVulnerabilitiesByCve(1L, null, "", 0, 50, null);
        assertEquals(4, all.get("totalElements"));
        assertEquals("all", all.get("view"));
        // Both totals are reported regardless of the active view, so the toggle can be labelled.
        assertEquals(4, all.get("totalCveCount"));
        assertEquals(1L, all.get("runtimeExposedCount"));

        var runtime = service.getVulnerabilitiesByCve(1L, null, "", 0, 50, "runtime");
        assertEquals("runtime", runtime.get("view"));
        assertEquals(1, runtime.get("totalElements"));
        @SuppressWarnings("unchecked")
        var rows = (List<java.util.Map<String, Object>>) runtime.get("content");
        assertEquals("CVE-1", rows.get(0).get("cveId"));
        assertEquals(true, rows.get(0).get("runtimeExposed"));
        // Unknown view values must not silently filter anything out.
        assertEquals(4, service.getVulnerabilitiesByCve(1L, null, "", 0, 50, "nonsense")
                .get("totalElements"));
    }

    @Test
    void cveRuntimeFlagsAreOrredAcrossFindings() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        // One CVE, two findings: neither is both in-use AND exposed on its own, but the CVE is
        // reachable because one instance is loaded and another sits behind ingress.
        when(vulnRepo.findAllForCveGrouping(List.of("111"))).thenReturn(List.of(
                cveRow("CVE-9", "HIGH", "res-a", true, false, false),
                cveRow("CVE-9", "HIGH", "res-b", false, true, false)));

        var runtime = service.getVulnerabilitiesByCve(1L, null, "", 0, 50, "runtime");
        assertEquals(1, runtime.get("totalElements"));
        @SuppressWarnings("unchecked")
        var rows = (List<java.util.Map<String, Object>>) runtime.get("content");
        assertEquals(2, rows.get(0).get("findingsCount"));
        assertEquals(2, rows.get(0).get("resourceCount"));
    }

    @Test
    void vulnerabilityGroupsRollUpByImageAndOrRuntimeFlags() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        // groupByImage columns: key, secondary, severity, cveId, resourceId, exploitable,
        //                       packageInUse, internetExposed
        when(vulnRepo.groupByImage(List.of("111"))).thenReturn(List.of(
                new Object[]{"api", "v1.2", "HIGH", "CVE-1", "res-a", true, true, false},
                new Object[]{"api", "v1.2", "CRITICAL", "CVE-2", "res-b", false, false, true},
                new Object[]{"worker", "v3", "LOW", "CVE-3", "res-c", false, false, false}));

        var out = service.getVulnerabilityGroups(1L, null, "image", 0, 50);

        assertEquals("image", out.get("dimension"));
        assertEquals(2, out.get("totalElements"));
        @SuppressWarnings("unchecked")
        var rows = (List<java.util.Map<String, Object>>) out.get("content");
        // Worst severity wins the group, so "api" (CRITICAL) sorts above "worker" (LOW).
        var api = rows.get(0);
        assertEquals("api", api.get("key"));
        assertEquals("CRITICAL", api.get("severity"));
        assertEquals(2, api.get("cveCount"));
        assertEquals(2, api.get("findingsCount"));
        assertEquals(2, api.get("resourceCount"));
        // Flags are OR-ed: neither finding is both in-use AND exposed, but the image is both.
        assertEquals(true, api.get("packageInUse"));
        assertEquals(true, api.get("internetExposed"));
        assertEquals(true, api.get("runtimeExposed"));
        assertEquals("worker", rows.get(1).get("key"));
    }

    @Test
    void vulnerabilityGroupsRejectUnknownDimensionRatherThanDefaulting() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        var out = service.getVulnerabilityGroups(1L, null, "colour", 0, 50);
        // Must not quietly return images: a typo would look like real data for the wrong axis.
        assertEquals(0, out.get("totalElements"));
        assertEquals("colour", out.get("dimension"));
        verify(vulnRepo, never()).groupByImage(anyList());
    }

    @Test
    void threatGroupsRollUpByThreatAndResource() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        // findGroupingFacets columns: title, severity, resourceId, resourceName, resourceType,
        //                             cloudAccountId, category, occurrences, firstSeen, lastSeen
        var t0 = java.time.Instant.parse("2026-08-01T00:00:00Z");
        var t1 = java.time.Instant.parse("2026-08-05T00:00:00Z");
        when(detectionRepo.findGroupingFacets(List.of("111"))).thenReturn(List.of(
                new Object[]{"Reverse shell", "HIGH", "r1", "web-1", "K8S", "111", "SENSOR", 3, t0, t1},
                new Object[]{"Reverse shell", "CRITICAL", "r2", "web-2", "K8S", "111", "SENSOR", 1, t0, t1},
                new Object[]{"MFA disabled", "MEDIUM", "r3", "user-1", "IAM", "111", "CLOUD_TRAIL", 2, t0, t0}));

        var byThreat = service.getThreatGroups(1L, null, "threat");
        @SuppressWarnings("unchecked")
        var groups = (List<java.util.Map<String, Object>>) byThreat.get("groups");
        assertEquals(2, groups.size());
        var shell = groups.get(0);
        assertEquals("Reverse shell", shell.get("key"));
        assertEquals("CRITICAL", shell.get("severity"));   // worst in the group
        assertEquals(2, shell.get("detections"));
        assertEquals(4, shell.get("occurrences"));         // occurrence counts summed
        assertEquals(2, shell.get("resourceCount"));

        var byResource = service.getThreatGroups(1L, null, "resource");
        @SuppressWarnings("unchecked")
        var resources = (List<java.util.Map<String, Object>>) byResource.get("groups");
        assertEquals(3, resources.size());                  // one row per resource

        // Unknown dimension yields nothing rather than silently grouping by threat.
        assertTrue(((List<?>) service.getThreatGroups(1L, null, "weather").get("groups")).isEmpty());
    }

    /**
     * Shift-left scans carry no cloud_account_id — only repository, branch, commit and
     * initiator — and every client reads the same upwind.org-id. So resolveAccountIds cannot
     * scope them, and serving them broadly would leak another tenant's repository names and
     * contributor usernames. Owning every account in the org is the only ownership signal the
     * data supports.
     */
    @Test
    void codeScansAreWithheldFromAClientThatDoesNotOwnTheWholeOrg() {
        when(apiClient.isConfigured()).thenReturn(true);
        // Client 1 owns 111; the org also contains 222.
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111", "222"));

        var out = service.getCodeScans(1L, 50);

        assertEquals(false, out.get("available"));
        assertTrue(((List<?>) out.get("scans")).isEmpty());
        assertNotNull(out.get("unavailableReason"));
        // Must not even ask Upwind on behalf of a client that cannot see the answer.
        verify(apiClient, never()).searchShiftLeftScans(anyInt());
        assertTrue(service.getCodeScanFindings(1L, "abc").isEmpty());
        verify(apiClient, never()).getShiftLeftResult(anyString());
    }

    @Test
    void codeScansAreServedToTheOrgOwnerAndMapTheBuildContext() throws Exception {
        when(apiClient.isConfigured()).thenReturn(true);
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111"), aws("222")));
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111", "222"));
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        when(apiClient.searchShiftLeftScans(anyInt())).thenReturn(List.of(om.readTree(
                "{\"type\":\"IMAGE_BUILD\",\"source\":\"GITHUB\",\"image\":\"XamOps-New\","
                + "\"os\":\"linux\",\"fingerprint\":\"fp-1\",\"repository\":\"XamOps-New\","
                + "\"branch\":\"main\",\"initiator\":\"dev\",\"commit_sha\":\"abc123\","
                + "\"repository_scan_details\":{\"pr_link\":\"https://gh/pull/295\"},"
                + "\"create_time\":\"2026-07-13T07:23:52Z\"}")));

        var out = service.getCodeScans(1L, 50);

        assertEquals(true, out.get("available"));
        assertNull(out.get("unavailableReason"));
        @SuppressWarnings("unchecked")
        var scans = (List<java.util.Map<String, Object>>) out.get("scans");
        assertEquals(1, scans.size());
        assertEquals("fp-1", scans.get(0).get("fingerprint"));
        assertEquals("IMAGE_BUILD", scans.get(0).get("type"));
        assertEquals("abc123", scans.get(0).get("commitSha"));
        assertEquals("https://gh/pull/295", scans.get(0).get("pullRequestUrl"));
    }

    @Test
    void codeScanFindingsSplitIntroducedFromUnchangedAndSortBySeverity() throws Exception {
        when(apiClient.isConfigured()).thenReturn(true);
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(connectionRepo.findAll()).thenReturn(List.of());
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111"));
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        when(apiClient.getShiftLeftResult("fp-1")).thenReturn(om.readTree(
                "{\"fingerprint\":\"fp-1\",\"branch\":\"main\",\"image_os\":\"linux\","
                + "\"high_impact_cves\":[],"
                + "\"introduced_cves\":["
                + "{\"cve_id\":\"CVE-1\",\"severity\":\"MEDIUM\",\"score\":\"5.3\","
                + "\"package_name\":\"golang.org/x/crypto\",\"is_fix_available\":true,"
                + "\"fixed_in_version\":\"0.52.0\",\"is_exploitable\":false},"
                + "{\"cve_id\":\"CVE-2\",\"severity\":\"CRITICAL\",\"score\":\"9.1\","
                + "\"is_fix_available\":true,\"is_exploitable\":false}],"
                + "\"no_change_cves\":[{\"cve_id\":\"CVE-3\",\"severity\":\"LOW\"}]}"));

        var out = service.getCodeScanFindings(1L, "fp-1");

        @SuppressWarnings("unchecked")
        var counts = (java.util.Map<String, Integer>) out.get("counts");
        // Bucket names are camelised so the client reads one convention.
        assertEquals(2, counts.get("introduced"));
        assertEquals(1, counts.get("noChange"));
        assertEquals(0, counts.get("highImpact"));
        @SuppressWarnings("unchecked")
        var cves = (java.util.Map<String, List<java.util.Map<String, Object>>>) out.get("cves");
        // Worst first: a reviewer reads the top of the list, so CRITICAL cannot sort below MEDIUM.
        assertEquals("CVE-2", cves.get("introduced").get(0).get("cveId"));
        assertEquals("0.52.0", cves.get("introduced").get(1).get("fixedInVersion"));
    }

    @Test
    void threatOverviewHonoursSupportedWindowsAndClampsOthers() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(apiClient.isConfigured()).thenReturn(false);
        var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.Instant recent = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                .plusSeconds(3600);
        java.time.Instant twentyDaysAgo = recent.minusSeconds(60L * 60 * 24 * 20);
        when(detectionRepo.findDashboardFacets(List.of("111"))).thenReturn(List.of(
                new Object[]{"HIGH", "Execution", "SENSOR", recent},
                new Object[]{"LOW", "Execution", "SENSOR", twentyDaysAgo}));

        var week = service.getThreatOverview(1L, null, 7);
        assertEquals(7, week.get("trendDays"));
        @SuppressWarnings("unchecked")
        var weekTrend = (List<java.util.Map<String, Object>>) week.get("trend");
        assertEquals(7, weekTrend.size());
        // The 20-day-old detection is counted in the totals but falls outside the window, which
        // is exactly the case the in-window figure exists to explain.
        assertEquals(2, week.get("detectionCount"));
        assertEquals(1L, week.get("detectionsInWindow"));

        var month = service.getThreatOverview(1L, null, 30);
        assertEquals(30, month.get("trendDays"));
        assertEquals(2L, month.get("detectionsInWindow"));

        // Anything outside the supported set falls back rather than letting a caller ask for
        // an arbitrary number of daily buckets.
        assertEquals(7, service.getThreatOverview(1L, null, 4000).get("trendDays"));
        assertEquals(7, service.getThreatOverview(1L, null, -1).get("trendDays"));
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
