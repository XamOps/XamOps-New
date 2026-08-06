package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.UpwindThreatDetection;
import com.xammer.cloud.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UpwindSyncServiceTest {

    private UpwindApiClient apiClient;
    private UpwindThreatDetectionRepository detectionRepo;
    private UpwindVulnerabilityFindingRepository vulnRepo;
    private UpwindPostureFindingRepository postureRepo;
    private UpwindSyncStateRepository syncStateRepo;
    private UpwindInventoryAssetRepository inventoryRepo;
    private UpwindApiEndpointRepository apiEndpointRepo;
    private UpwindAccountConnectionRepository connectionRepo;
    private UpwindSyncService service;

    private static final String DETECTION_JSON =
        "{\"id\":\"uwnd-1\",\"category\":\"CLOUD_TRAIL\",\"severity\":\"MEDIUM\",\"status\":\"OPEN\"," +
        "\"title\":\"Console login without MFA\"," +
        "\"resource\":{\"id\":\"ep-1\",\"name\":\"kartik\",\"type\":\"AWS_IAM_USER\"," +
        "\"cloud_provider\":\"AWS\",\"cloud_account_id\":\"605134457560\"}," +
        "\"first_seen_time\":\"2026-07-02T08:41:06Z\",\"last_seen_time\":\"2026-07-02T18:20:06Z\"," +
        "\"occurrence_count\":2," +
        "\"mitre_attacks\":[{\"tactic_id\":\"TA0001\",\"tactic_name\":\"Initial Access\"," +
        "\"technique_id\":\"T1078\",\"technique_name\":\"Valid Accounts\"}]}";

    @BeforeEach
    void setUp() {
        apiClient = mock(UpwindApiClient.class);
        detectionRepo = mock(UpwindThreatDetectionRepository.class);
        vulnRepo = mock(UpwindVulnerabilityFindingRepository.class);
        postureRepo = mock(UpwindPostureFindingRepository.class);
        syncStateRepo = mock(UpwindSyncStateRepository.class);
        inventoryRepo = mock(UpwindInventoryAssetRepository.class);
        apiEndpointRepo = mock(UpwindApiEndpointRepository.class);
        connectionRepo = mock(UpwindAccountConnectionRepository.class);
        when(syncStateRepo.findByDomain(any())).thenReturn(Optional.empty());
        when(apiEndpointRepo.findByUpwindId(any())).thenReturn(Optional.empty());
        when(connectionRepo.findAll()).thenReturn(Collections.emptyList());
        service = new UpwindSyncService(apiClient, detectionRepo, vulnRepo, postureRepo,
                syncStateRepo, inventoryRepo, apiEndpointRepo, connectionRepo);
    }

    private JsonNode parse(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void syncDetectionsInsertsNewDetection() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(List.of(parse(DETECTION_JSON)));
        when(detectionRepo.findByUpwindId("uwnd-1")).thenReturn(Optional.empty());

        service.syncDetections();

        ArgumentCaptor<UpwindThreatDetection> captor = ArgumentCaptor.forClass(UpwindThreatDetection.class);
        verify(detectionRepo).save(captor.capture());
        UpwindThreatDetection saved = captor.getValue();
        assertEquals("uwnd-1", saved.getUpwindId());
        assertEquals("MEDIUM", saved.getSeverity());
        assertEquals("605134457560", saved.getCloudAccountId());
        assertEquals("Initial Access", saved.getMitreTactics());
        assertEquals(2, saved.getOccurrenceCount());
        assertNotNull(saved.getSyncedAt());
    }

    @Test
    void syncDetectionsUpdatesExistingByUpwindId() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(List.of(parse(DETECTION_JSON)));
        UpwindThreatDetection existing = new UpwindThreatDetection();
        existing.setId(7L);
        existing.setUpwindId("uwnd-1");
        when(detectionRepo.findByUpwindId("uwnd-1")).thenReturn(Optional.of(existing));

        service.syncDetections();

        ArgumentCaptor<UpwindThreatDetection> captor = ArgumentCaptor.forClass(UpwindThreatDetection.class);
        verify(detectionRepo).save(captor.capture());
        assertEquals(7L, captor.getValue().getId());
        assertEquals(2, captor.getValue().getOccurrenceCount());
    }

    @Test
    void syncSkipsWhenNotConfigured() {
        when(apiClient.isConfigured()).thenReturn(false);
        service.syncDetections();
        service.syncAll();
        verifyNoInteractions(detectionRepo, vulnRepo, postureRepo);
    }

    @Test
    void syncRecordsErrorInSyncState() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenThrow(new IllegalStateException("boom"));

        service.syncDetections();

        verify(syncStateRepo, atLeastOnce()).save(argThat(s ->
                "DETECTIONS".equals(s.getDomain()) && "boom".equals(s.getLastError())));
    }

    private static final String VULN_JSON =
        "{\"id\":\"uvf-1\",\"vulnerability\":{\"nvd_cve_id\":\"CVE-1\"}," +
        "\"resource\":{\"cloud_account_id\":\"111\"}}";

    private static final String POSTURE_JSON =
        "{\"id\":\"fnd-1\",\"status\":\"FAIL\",\"resource\":{\"cloud_account_id\":\"111\"}}";

    /** Reflection-free builder for the client's package-private FetchResult constructor. */
    private static UpwindApiClient.FetchResult result(java.util.List<JsonNode> items, boolean truncated) {
        return new UpwindApiClient.FetchResult(items, truncated);
    }

    @Test
    void syncAllMarksStaleVulnsResolved() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchVulnerabilityFindingsPaged(anyInt()))
                .thenReturn(result(List.of(parse(VULN_JSON)), false));
        when(apiClient.fetchConfigurationFindingsPaged(anyString(), anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), false));

        service.syncAll();

        verify(vulnRepo).markResolvedNotSyncedSince(any());
        verify(postureRepo).markResolvedNotSyncedSince(any());
    }

    @Test
    void syncAllSkipsResolveWhenFetchEmpty() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchVulnerabilityFindingsPaged(anyInt()))
                .thenReturn(result(Collections.emptyList(), false));
        when(apiClient.fetchConfigurationFindingsPaged(anyString(), anyInt()))
                .thenReturn(result(Collections.emptyList(), false));

        service.syncAll();

        verify(vulnRepo, never()).markResolvedNotSyncedSince(any());
        verify(postureRepo, never()).markResolvedNotSyncedSince(any());
    }

    @Test
    void syncApiEndpointsUpsertsPerAccountAndReconciles() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111"));
        JsonNode ep = parse("{\"id\":\"rt-1\",\"method\":\"GET\",\"uri\":\"/version\","
                + "\"resource_id\":\"ep-1\","
                + "\"risk_overview\":{\"internet_exposure\":{\"ingress\":"
                + "{\"last_seen_time\":\"2026-07-28T08:00:20Z\"}},"
                + "\"authentication\":{\"state\":\"UNAUTHENTICATED\"}}}");
        when(apiClient.searchApiEndpoints(eq("111"), anyInt()))
                .thenReturn(result(List.of(ep), false));

        service.syncApiEndpoints();

        ArgumentCaptor<com.xammer.cloud.domain.UpwindApiEndpoint> cap =
                ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindApiEndpoint.class);
        verify(apiEndpointRepo).save(cap.capture());
        assertEquals("rt-1", cap.getValue().getUpwindId());
        assertEquals("111", cap.getValue().getCloudAccountId());
        assertTrue(cap.getValue().isInternetExposed());
        assertEquals("UNAUTHENTICATED", cap.getValue().getAuthState());
        verify(apiEndpointRepo).markResolvedNotSyncedSince(eq("111"), any());
    }

    /**
     * The outage this guard exists to prevent: reconciliation had been standing down whenever
     * the lease lapsed mid-sync, a lease heartbeat turned it back on, and the next run archived
     * a table full of rows the fetch never re-covered — emptying every XamSecure tab.
     *
     * A complete walk is not evidence the walk saw everything: it only means no page cap was
     * hit. An auth or scope change yields a small, complete, wrong result.
     */
    @Test
    void archivingIsRefusedWhenTheRunCoversTooLittleOfWhatWeHold() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchVulnerabilityFindingsPaged(anyInt()))
                .thenReturn(result(List.of(parse(VULN_JSON)), false));
        when(apiClient.fetchConfigurationFindingsPaged(anyString(), anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), false));
        // One finding fetched against 10,000 held: 0.01% coverage, far under the floor.
        when(vulnRepo.countOpen()).thenReturn(10_000L);
        when(postureRepo.countLive()).thenReturn(10_000L);

        service.syncAll();

        verify(vulnRepo, never()).markResolvedNotSyncedSince(any());
        verify(postureRepo, never()).markResolvedNotSyncedSince(any());
    }

    @Test
    void archivingProceedsWhenTheRunCoversEnough() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchVulnerabilityFindingsPaged(anyInt()))
                .thenReturn(result(List.of(parse(VULN_JSON)), false));
        when(apiClient.fetchConfigurationFindingsPaged(anyString(), anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), false));
        // One fetched against one held is full coverage; a genuinely emptied estate still
        // reconciles, which is the case the guard must not block.
        when(vulnRepo.countOpen()).thenReturn(1L);
        when(postureRepo.countLive()).thenReturn(2L);   // FAIL+PASS walks upsert twice

        service.syncAll();

        verify(vulnRepo).markResolvedNotSyncedSince(any());
        verify(postureRepo).markResolvedNotSyncedSince(any());
    }

    @Test
    void endpointArchivingIsRefusedPerAccountWhenCoverageIsTooLow() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111"));
        when(apiClient.searchApiEndpoints(eq("111"), anyInt()))
                .thenReturn(result(List.of(parse("{\"id\":\"rt-1\"}")), false));
        when(apiEndpointRepo.countActiveByAccount("111")).thenReturn(15_000L);

        service.syncApiEndpoints();

        // Still upserted — refusing to archive must not stop us storing what we did fetch.
        verify(apiEndpointRepo).save(any());
        verify(apiEndpointRepo, never()).markResolvedNotSyncedSince(any(), any());
    }

    @Test
    void syncApiEndpointsSkipsReconcileWhenTruncated() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111"));
        when(apiClient.searchApiEndpoints(eq("111"), anyInt()))
                .thenReturn(result(List.of(parse("{\"id\":\"rt-1\"}")), true));

        service.syncApiEndpoints();

        verify(apiEndpointRepo, never()).markResolvedNotSyncedSince(any(), any());
    }

    @Test
    void syncApiEndpointsNoOpsWithoutKnownAccounts() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(Collections.emptyList());

        service.syncApiEndpoints();

        verify(apiClient, never()).searchApiEndpoints(any(), anyInt());
    }

    @Test
    void syncAllSkipsResolveWhenFetchTruncated() {
        // A partial page walk must never archive findings it simply never re-read —
        // that is what collapsed Issues/Attack Surface to a single page of S3 findings.
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchVulnerabilityFindingsPaged(anyInt()))
                .thenReturn(result(List.of(parse(VULN_JSON)), true));
        when(apiClient.fetchConfigurationFindingsPaged(anyString(), anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), true));

        service.syncAll();

        verify(vulnRepo, never()).markResolvedNotSyncedSince(any());
        verify(postureRepo, never()).markResolvedNotSyncedSince(any());
    }

    @Test
    void syncInventoryUpsertsAssets() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("605134457560"));
        // Legacy flat-search spelling: asset_category / asset_sub_category / label.
        String assetJson = "{\"id\":\"uwr-1\",\"asset_category\":\"compute_platform\"," +
            "\"asset_sub_category\":\"virtual_machine\",\"label\":\"aws_ec2_instance\"," +
            "\"cloud_provider\":\"AWS\",\"cloud_account_id\":\"605134457560\"," +
            "\"cloud_resource_id\":\"i-0613\",\"name\":\"XamCloud-Website\",\"region\":\"ap-south-2\"}";
        when(apiClient.searchInventory(anyList(), anyInt())).thenReturn(List.of(parse(assetJson)));
        when(inventoryRepo.findByUpwindId("uwr-1")).thenReturn(Optional.empty());

        service.syncInventory();

        org.mockito.ArgumentCaptor<com.xammer.cloud.domain.UpwindInventoryAsset> cap =
            org.mockito.ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindInventoryAsset.class);
        verify(inventoryRepo).save(cap.capture());
        assertEquals("uwr-1", cap.getValue().getUpwindId());
        assertEquals("605134457560", cap.getValue().getCloudAccountId());
        assertEquals("aws_ec2_instance", cap.getValue().getLabel());
        verify(inventoryRepo).markResolvedNotSyncedSince(any());
    }

    /**
     * The catalog payload uses category/sub_category/resource_type instead of
     * asset_category/asset_sub_category/label, and carries the risk objects the flat search
     * omitted. Both spellings must land in the same columns.
     */
    @Test
    void syncInventoryMapsCatalogFieldsAndRiskRollups() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("605134457560"));
        String assetJson = "{\"id\":\"uwr-2\",\"category\":\"identity\","
            + "\"sub_category\":\"role\",\"resource_type\":\"aws_iam_role\","
            + "\"cloud_provider\":\"aws\",\"cloud_account_id\":\"605134457560\","
            + "\"cloud_resource_id\":\"arn:aws:iam::605134457560:role/admin\","
            + "\"name\":\"admin\",\"region\":\"global\","
            + "\"high_privilege_risk\":{\"has_elevated_privilege\":true},"
            + "\"sensitive_data_at_rest\":{\"has_pii\":true,\"has_phi\":false,\"has_pci\":false},"
            + "\"sensitive_data_in_transit\":{\"has_pii\":false},"
            + "\"vulnerability_risk\":{\"critical_count\":2,\"high_count\":3,\"total_count\":9},"
            + "\"detection_risk\":{\"critical_count\":0,\"high_count\":1,\"total_count\":4},"
            + "\"network_risk\":{\"internet_exposure_type\":\"direct\","
            + "\"last_communication_with_internet_time\":\"2026-08-01T09:03:20Z\"},"
            + "\"technologies\":[{\"name\":\"openssl\",\"version\":\"1.0.2\","
            + "\"end_of_life_time\":\"2019-12-31T00:00:00Z\"},{\"name\":\"nginx\",\"version\":\"1.25\"}]}";
        when(apiClient.searchInventory(anyList(), anyInt())).thenReturn(List.of(parse(assetJson)));
        when(inventoryRepo.findByUpwindId("uwr-2")).thenReturn(Optional.empty());

        service.syncInventory();

        org.mockito.ArgumentCaptor<com.xammer.cloud.domain.UpwindInventoryAsset> cap =
            org.mockito.ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindInventoryAsset.class);
        verify(inventoryRepo).save(cap.capture());
        var a = cap.getValue();
        assertEquals("identity", a.getAssetCategory());
        assertEquals("role", a.getAssetSubCategory());
        assertEquals("aws_iam_role", a.getLabel());
        assertEquals(Boolean.TRUE, a.getHasElevatedPrivilege());
        assertEquals(Boolean.TRUE, a.getSensitivePiiAtRest());
        assertEquals(Boolean.FALSE, a.getSensitivePciAtRest());
        assertEquals(Integer.valueOf(2), a.getVulnCriticalCount());
        assertEquals(Integer.valueOf(9), a.getVulnTotalCount());
        assertEquals(Integer.valueOf(4), a.getDetectionTotalCount());
        assertEquals("direct", a.getInternetExposureType());
        assertNotNull(a.getLastInternetCommunication());
        assertEquals(Boolean.TRUE, a.getHasEolTechnology());
        assertTrue(a.getTechnologies().contains("openssl 1.0.2 (EOL)"));
        assertTrue(a.getTechnologies().contains("nginx 1.25"));
    }

    /** Absent risk objects must stay null, so "unknown" never reads as "none". */
    @Test
    void syncInventoryLeavesRiskNullWhenUpwindOmitsIt() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111"));
        when(apiClient.searchInventory(anyList(), anyInt())).thenReturn(List.of(parse(
                "{\"id\":\"uwr-3\",\"cloud_account_id\":\"111\",\"high_privilege_risk\":{},"
                + "\"vulnerability_risk\":{}}")));
        when(inventoryRepo.findByUpwindId("uwr-3")).thenReturn(Optional.empty());

        service.syncInventory();

        org.mockito.ArgumentCaptor<com.xammer.cloud.domain.UpwindInventoryAsset> cap =
            org.mockito.ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindInventoryAsset.class);
        verify(inventoryRepo).save(cap.capture());
        assertNull(cap.getValue().getHasElevatedPrivilege());
        assertNull(cap.getValue().getVulnTotalCount());
        assertNull(cap.getValue().getTechnologies());
    }

    @Test
    void syncInventorySkipsMarkResolvedWhenNoItemsReturned() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111"));
        when(apiClient.searchInventory(anyList(), anyInt())).thenReturn(Collections.emptyList());

        service.syncInventory();

        verify(inventoryRepo, never()).markResolvedNotSyncedSince(any());
    }

    /**
     * The catalog search filters on cloud_account_id; cloud_provider is not a supported
     * catalog filter and silently returns an empty page, so the sync must key off accounts.
     */
    @Test
    void syncInventoryFiltersByCloudAccountId() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111", "222"));
        when(apiClient.searchInventory(anyList(), anyInt())).thenReturn(Collections.emptyList());

        service.syncInventory();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<java.util.Map<String, Object>>> cap =
                ArgumentCaptor.forClass(List.class);
        verify(apiClient, times(2)).searchInventory(cap.capture(), anyInt());
        assertEquals("cloud_account_id", cap.getAllValues().get(0).get(0).get("field"));
        assertEquals(List.of("111"), cap.getAllValues().get(0).get(0).get("value"));
        assertEquals(List.of("222"), cap.getAllValues().get(1).get(0).get("value"));
    }

    /** Bootstrap path: with no assets yet, the connection table supplies the account list. */
    @Test
    void syncInventoryBootstrapsFromRegisteredConnections() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(Collections.emptyList());
        com.xammer.cloud.domain.UpwindAccountConnection conn =
                new com.xammer.cloud.domain.UpwindAccountConnection();
        conn.setCloudAccountId("598515435126");
        when(connectionRepo.findAll()).thenReturn(List.of(conn));
        when(apiClient.searchInventory(anyList(), anyInt())).thenReturn(Collections.emptyList());

        service.syncInventory();

        verify(apiClient).searchInventory(anyList(), anyInt());
    }

    @Test
    void syncInventoryNoOpsWithoutKnownAccounts() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(Collections.emptyList());

        service.syncInventory();

        verify(apiClient, never()).searchInventory(anyList(), anyInt());
    }

    // --- Vulnerabilities: runtime exposure and package/image/K8s context ---

    /**
     * Every one of these fields was arriving in the payload and being discarded on all
     * ~126k findings, which is why the Vulnerabilities tab could only show installed counts.
     */
    @Test
    void syncVulnerabilitiesPersistsRuntimeExposureAndContext() {
        when(apiClient.isConfigured()).thenReturn(true);
        String json = "{\"id\":\"uvf-1\",\"source\":\"SENSOR\","
                + "\"first_seen_time\":\"2026-08-01T12:08:31Z\","
                + "\"last_scan_time\":\"2026-08-03T18:36:32Z\","
                + "\"vulnerability\":{\"nvd_cve_id\":\"CVE-2025-1148\","
                + "\"nvd_cvss_v3_severity\":\"LOW\",\"nvd_cvss_v3_score\":\"3.1\","
                + "\"nvd_cvss_v2_score\":\"2.6\",\"nvd_cvss_v2_severity\":\"LOW\","
                + "\"nvd_cvss_v4_score\":\"2.3\",\"nvd_cvss_v4_severity\":\"LOW\","
                + "\"epss_score\":\"0.00628\",\"epss_severity\":\"LOW\",\"exploitable\":true,"
                + "\"known_exploit_sources\":[\"nist\",\"cisa\"],"
                + "\"exploitable_functions_in_use\":[\"link_or_copy\"],"
                + "\"cve_first_seen_time\":\"2026-08-01T10:52:34Z\"},"
                + "\"package\":{\"name\":\"binutils\",\"version\":\"2.44-3\","
                + "\"type\":\"IMAGE\",\"framework\":\"os\",\"in_use\":true},"
                + "\"image\":{\"name\":\"inv-v2-file-use\",\"tag\":\"uat-085ab6e\","
                + "\"uri\":\"598515435126.dkr.ecr.ap-south-1.amazonaws.com/inv-v2:uat-085\","
                + "\"os_name\":\"Debian GNU/Linux\",\"os_version\":\"13\"},"
                + "\"resource\":{\"id\":\"ep-cf7a\",\"name\":\"inv-v2-file-use\","
                + "\"type\":\"KUBERNETES_DEPLOYMENT\",\"cloud_account_id\":\"598515435126\","
                + "\"cluster_id\":\"4122e4ce\",\"cluster_name\":\"final-onfi-infra\","
                + "\"namespace\":\"investigation-uat\","
                + "\"internet_exposure\":{\"ingress\":{\"exposed\":true,"
                + "\"active_communication\":false}}}}";
        when(apiClient.fetchVulnerabilityFindingsPaged(anyInt()))
                .thenReturn(result(List.of(parse(json)), false));
        when(apiClient.fetchConfigurationFindingsPaged(anyString(), anyInt()))
                .thenReturn(result(Collections.emptyList(), false));
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(vulnRepo.findByUpwindId("uvf-1")).thenReturn(Optional.empty());

        service.syncAll();

        ArgumentCaptor<com.xammer.cloud.domain.UpwindVulnerabilityFinding> cap =
                ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindVulnerabilityFinding.class);
        verify(vulnRepo).save(cap.capture());
        var v = cap.getValue();
        // Runtime exposure — the signals that separate a live risk from an installed package.
        assertEquals(Boolean.TRUE, v.getPackageInUse());
        assertEquals(Boolean.TRUE, v.getInternetExposed());
        assertEquals(Boolean.FALSE, v.getActiveCommunication());
        assertEquals(Boolean.TRUE, v.getExploitableFunctionsInUse());
        assertEquals("link_or_copy", v.getExploitableFunctions());
        // Package / image context.
        assertEquals("binutils", v.getPackageName());
        assertEquals("2.44-3", v.getPackageVersion());
        assertEquals("IMAGE", v.getPackageType());
        assertEquals("os", v.getPackageFramework());
        assertEquals("inv-v2-file-use", v.getImageName());
        assertEquals("uat-085ab6e", v.getImageTag());
        assertEquals("Debian GNU/Linux", v.getImageOsName());
        // Kubernetes context.
        assertEquals("4122e4ce", v.getClusterId());
        assertEquals("final-onfi-infra", v.getClusterName());
        assertEquals("investigation-uat", v.getNamespace());
        // Scoring / provenance.
        assertEquals("LOW", v.getEpssSeverity());
        assertEquals("2.6", v.getCvssV2Score());
        assertEquals("2.3", v.getCvssV4Score());
        assertEquals("nist,cisa", v.getKnownExploitSources());
        assertNotNull(v.getCveFirstSeenTime());
        assertEquals("SENSOR", v.getSource());
    }

    /** An empty functions array means "observed, none in use" — false, not null. */
    @Test
    void syncVulnerabilitiesDistinguishesEmptyFunctionsFromAbsent() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchConfigurationFindingsPaged(anyString(), anyInt()))
                .thenReturn(result(Collections.emptyList(), false));
        when(apiClient.fetchVulnerabilityFindingsPaged(anyInt())).thenReturn(result(List.of(parse(
                "{\"id\":\"uvf-2\",\"vulnerability\":{\"nvd_cve_id\":\"CVE-1\","
                + "\"exploitable_functions_in_use\":[]},\"resource\":{},\"package\":{}}")), false));
        when(vulnRepo.findByUpwindId("uvf-2")).thenReturn(Optional.empty());

        service.syncAll();

        ArgumentCaptor<com.xammer.cloud.domain.UpwindVulnerabilityFinding> cap =
                ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindVulnerabilityFinding.class);
        verify(vulnRepo).save(cap.capture());
        assertEquals(Boolean.FALSE, cap.getValue().getExploitableFunctionsInUse());
        assertNull(cap.getValue().getExploitableFunctions());
        // Absent objects stay null rather than reading as a definite "no".
        assertNull(cap.getValue().getPackageInUse());
        assertNull(cap.getValue().getInternetExposed());
    }

    // --- Posture: PASS findings and Kubernetes context ---

    /**
     * The endpoint defaults to status=FAIL, so omitting the parameter meant PASS findings
     * were never fetched and every per-framework pass count was structurally zero.
     */
    @Test
    void syncPostureFetchesBothFailAndPassStatuses() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchConfigurationFindingsPaged(anyString(), anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), false));

        service.syncPosture();

        ArgumentCaptor<String> statuses = ArgumentCaptor.forClass(String.class);
        verify(apiClient, times(2))
                .fetchConfigurationFindingsPaged(statuses.capture(), anyInt());
        assertEquals(List.of("FAIL", "PASS"), statuses.getAllValues());
    }

    /** Reconciliation is all-or-nothing: one truncated status must block mark-resolved. */
    @Test
    void syncPostureSkipsReconcileWhenAnyStatusTruncated() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchConfigurationFindingsPaged(eq("FAIL"), anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), false));
        when(apiClient.fetchConfigurationFindingsPaged(eq("PASS"), anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), true));

        service.syncPosture();

        verify(postureRepo, never()).markResolvedNotSyncedSince(any());
    }

    @Test
    void syncPostureReconcilesWhenEveryStatusCompleted() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchConfigurationFindingsPaged(anyString(), anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), false));

        service.syncPosture();

        verify(postureRepo).markResolvedNotSyncedSince(any());
    }

    /**
     * cluster_id / namespace are already in the v1 payload — they were simply never mapped,
     * which is why Kubernetes findings showed an opaque composite resource id.
     */
    @Test
    void syncPostureMapsKubernetesClusterAndNamespace() {
        when(apiClient.isConfigured()).thenReturn(true);
        String k8s = "{\"id\":\"fnd-k8s\",\"severity\":\"HIGH\",\"status\":\"FAIL\","
                + "\"title\":\"Publicly exposed EKS deployment\","
                + "\"resource\":{\"id\":\"4122e4ce::pr-3193::cos-ai-agent\","
                + "\"name\":\"cos-ai-agent-v1\",\"type\":\"KUBERNETES_DEPLOYMENT\","
                + "\"cloud_account_id\":\"605134457560\","
                + "\"cluster_id\":\"4122e4ce-1ca2-408a-8fb9-fff348a8c525\","
                + "\"namespace\":\"pr-3193\"}}";
        when(apiClient.fetchConfigurationFindingsPaged(eq("FAIL"), anyInt()))
                .thenReturn(result(List.of(parse(k8s)), false));
        when(apiClient.fetchConfigurationFindingsPaged(eq("PASS"), anyInt()))
                .thenReturn(result(Collections.emptyList(), false));
        when(postureRepo.findByUpwindId("fnd-k8s")).thenReturn(Optional.empty());

        service.syncPosture();

        ArgumentCaptor<com.xammer.cloud.domain.UpwindPostureFinding> cap =
                ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindPostureFinding.class);
        verify(postureRepo).save(cap.capture());
        assertEquals("4122e4ce-1ca2-408a-8fb9-fff348a8c525", cap.getValue().getClusterId());
        assertEquals("pr-3193", cap.getValue().getNamespace());
    }

    /** A non-Kubernetes resource must leave the cluster fields null, not empty strings. */
    @Test
    void syncPostureLeavesKubernetesFieldsNullForCloudResources() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchConfigurationFindingsPaged(eq("FAIL"), anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), false));
        when(apiClient.fetchConfigurationFindingsPaged(eq("PASS"), anyInt()))
                .thenReturn(result(Collections.emptyList(), false));

        service.syncPosture();

        ArgumentCaptor<com.xammer.cloud.domain.UpwindPostureFinding> cap =
                ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindPostureFinding.class);
        verify(postureRepo).save(cap.capture());
        assertNull(cap.getValue().getClusterId());
        assertNull(cap.getValue().getNamespace());
    }

    /**
     * A run that outlived its lease must NOT archive. Two overlapping runs each marking
     * resolved against their own start time would wipe rows the other just wrote — the same
     * mass-archiving failure that left production at 100 FAIL against 12,613 RESOLVED.
     */
    @Test
    void reconciliationIsSkippedWhenTheSyncLeaseWasLost() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchVulnerabilityFindingsPaged(anyInt()))
                .thenReturn(result(List.of(parse(VULN_JSON)), false));
        when(apiClient.fetchConfigurationFindingsPaged(anyString(), anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), false));
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111"));
        when(apiClient.searchInventory(anyList(), anyInt()))
                .thenReturn(List.of(parse("{\"id\":\"uwr-1\",\"cloud_account_id\":\"111\"}")));
        when(apiClient.searchApiEndpoints(eq("111"), anyInt()))
                .thenReturn(result(List.of(parse("{\"id\":\"rt-1\"}")), false));

        service.syncAll(() -> false);   // lease lapsed mid-run

        verify(vulnRepo, never()).markResolvedNotSyncedSince(any());
        verify(postureRepo, never()).markResolvedNotSyncedSince(any());
        verify(inventoryRepo, never()).markResolvedNotSyncedSince(any());
        verify(apiEndpointRepo, never()).markResolvedNotSyncedSince(any(), any());
    }

    /** The same run still archives normally while the lease is held. */
    @Test
    void reconciliationRunsWhenTheLeaseIsStillHeld() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchVulnerabilityFindingsPaged(anyInt()))
                .thenReturn(result(List.of(parse(VULN_JSON)), false));
        when(apiClient.fetchConfigurationFindingsPaged(anyString(), anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), false));
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111"));
        when(apiClient.searchInventory(anyList(), anyInt()))
                .thenReturn(List.of(parse("{\"id\":\"uwr-1\",\"cloud_account_id\":\"111\"}")));
        when(apiClient.searchApiEndpoints(eq("111"), anyInt()))
                .thenReturn(result(List.of(parse("{\"id\":\"rt-1\"}")), false));

        service.syncAll(() -> true);

        verify(vulnRepo).markResolvedNotSyncedSince(any());
        verify(postureRepo).markResolvedNotSyncedSince(any());
        verify(inventoryRepo).markResolvedNotSyncedSince(any());
        verify(apiEndpointRepo).markResolvedNotSyncedSince(eq("111"), any());
    }

    /** v2-only endpoint fields: sensitive-data findings and observed status codes. */
    @Test
    void syncApiEndpointsPersistsSensitiveDataAndStatusCodes() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111"));
        when(apiClient.searchApiEndpoints(eq("111"), anyInt())).thenReturn(result(List.of(parse(
                "{\"id\":\"rt-9\",\"method\":\"GET\",\"uri\":\"/api/profile\","
                + "\"status_codes\":[\"200\",\"401\"],"
                + "\"risk_overview\":{\"sensitive_data\":[{\"category\":\"PII\",\"type\":\"Email\"}]}}")),
                false));

        service.syncApiEndpoints();

        ArgumentCaptor<com.xammer.cloud.domain.UpwindApiEndpoint> cap =
                ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindApiEndpoint.class);
        verify(apiEndpointRepo).save(cap.capture());
        assertEquals("PII:Email", cap.getValue().getSensitiveData());
        assertEquals(Boolean.TRUE, cap.getValue().getHasSensitiveData());
        assertEquals("200,401", cap.getValue().getStatusCodes());
    }

    // --- Webhook event-type routing (ingestEvent) ---

    @Test
    void ingestEventRoutesVulnerabilityByType() {
        String r = service.ingestEvent(parse(
            "{\"id\":\"uvf-1\",\"type\":\"vulnerability\",\"vulnerability\":{\"nvd_cve_id\":\"CVE-1\"}," +
            "\"resource\":{\"cloud_account_id\":\"111\"}}"));
        assertEquals("vulnerability", r);
        verify(vulnRepo).findByUpwindId("uvf-1");
        verifyNoInteractions(detectionRepo);
    }

    @Test
    void ingestEventRoutesPostureByType() {
        String r = service.ingestEvent(parse(
            "{\"id\":\"fnd-1\",\"event_type\":\"posture_finding\",\"status\":\"FAIL\"," +
            "\"resource\":{\"cloud_account_id\":\"111\"}}"));
        assertEquals("posture", r);
        verify(postureRepo).findByUpwindId("fnd-1");
    }

    @Test
    void ingestEventRoutesInventoryByType() {
        String r = service.ingestEvent(parse(
            "{\"id\":\"uwr-1\",\"object_type\":\"inventory_asset\",\"asset_category\":\"compute_platform\"," +
            "\"label\":\"aws_ec2_instance\",\"cloud_provider\":\"AWS\",\"cloud_account_id\":\"605134457560\"," +
            "\"name\":\"XamCloud-Website\",\"region\":\"ap-south-2\"}"));
        assertEquals("inventory", r);
        verify(inventoryRepo).findByUpwindId("uwr-1");
    }

    @Test
    void ingestEventDefaultsToDetectionWhenTypeUnknown() {
        String r = service.ingestEvent(parse(DETECTION_JSON));
        assertEquals("detection", r);
        verify(detectionRepo).findByUpwindId("uwnd-1");
    }
}
