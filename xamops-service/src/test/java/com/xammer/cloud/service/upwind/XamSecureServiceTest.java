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
