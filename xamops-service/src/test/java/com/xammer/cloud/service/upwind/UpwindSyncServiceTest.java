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
import static org.mockito.Mockito.*;

class UpwindSyncServiceTest {

    private UpwindApiClient apiClient;
    private UpwindThreatDetectionRepository detectionRepo;
    private UpwindVulnerabilityFindingRepository vulnRepo;
    private UpwindPostureFindingRepository postureRepo;
    private UpwindSyncStateRepository syncStateRepo;
    private UpwindInventoryAssetRepository inventoryRepo;
    private UpwindApiEndpointRepository apiEndpointRepo;
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
        when(syncStateRepo.findByDomain(any())).thenReturn(Optional.empty());
        when(apiEndpointRepo.findByUpwindId(any())).thenReturn(Optional.empty());
        service = new UpwindSyncService(apiClient, detectionRepo, vulnRepo, postureRepo,
                syncStateRepo, inventoryRepo, apiEndpointRepo);
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
        when(apiClient.fetchConfigurationFindingsPaged(anyInt()))
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
        when(apiClient.fetchConfigurationFindingsPaged(anyInt()))
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
        when(apiClient.fetchApiSecurityEndpointsPaged(eq("111"), anyInt()))
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

    @Test
    void syncApiEndpointsSkipsReconcileWhenTruncated() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(List.of("111"));
        when(apiClient.fetchApiSecurityEndpointsPaged(eq("111"), anyInt()))
                .thenReturn(result(List.of(parse("{\"id\":\"rt-1\"}")), true));

        service.syncApiEndpoints();

        verify(apiEndpointRepo, never()).markResolvedNotSyncedSince(any(), any());
    }

    @Test
    void syncApiEndpointsNoOpsWithoutKnownAccounts() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(inventoryRepo.findDistinctCloudAccountIds()).thenReturn(Collections.emptyList());

        service.syncApiEndpoints();

        verify(apiClient, never()).fetchApiSecurityEndpointsPaged(any(), anyInt());
    }

    @Test
    void syncAllSkipsResolveWhenFetchTruncated() {
        // A partial page walk must never archive findings it simply never re-read —
        // that is what collapsed Issues/Attack Surface to a single page of S3 findings.
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchVulnerabilityFindingsPaged(anyInt()))
                .thenReturn(result(List.of(parse(VULN_JSON)), true));
        when(apiClient.fetchConfigurationFindingsPaged(anyInt()))
                .thenReturn(result(List.of(parse(POSTURE_JSON)), true));

        service.syncAll();

        verify(vulnRepo, never()).markResolvedNotSyncedSince(any());
        verify(postureRepo, never()).markResolvedNotSyncedSince(any());
    }

    @Test
    void syncInventoryUpsertsAssets() {
        when(apiClient.isConfigured()).thenReturn(true);
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

    @Test
    void syncInventorySkipsMarkResolvedWhenNoItemsReturned() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchInventory(anyList(), anyInt())).thenReturn(Collections.emptyList());

        service.syncInventory();

        verify(inventoryRepo, never()).markResolvedNotSyncedSince(any());
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
