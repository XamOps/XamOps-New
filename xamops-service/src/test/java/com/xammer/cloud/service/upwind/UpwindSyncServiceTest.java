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
import static org.mockito.Mockito.*;

class UpwindSyncServiceTest {

    private UpwindApiClient apiClient;
    private UpwindThreatDetectionRepository detectionRepo;
    private UpwindVulnerabilityFindingRepository vulnRepo;
    private UpwindPostureFindingRepository postureRepo;
    private UpwindSyncStateRepository syncStateRepo;
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
        when(syncStateRepo.findByDomain(any())).thenReturn(Optional.empty());
        service = new UpwindSyncService(apiClient, detectionRepo, vulnRepo, postureRepo, syncStateRepo);
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

    @Test
    void syncAllMarksStaleVulnsResolved() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchVulnerabilityFindings(anyInt())).thenReturn(List.of(parse(VULN_JSON)));
        when(apiClient.fetchConfigurationFindings(anyInt())).thenReturn(List.of(parse(POSTURE_JSON)));

        service.syncAll();

        verify(vulnRepo).markResolvedNotSyncedSince(any());
        verify(postureRepo).markResolvedNotSyncedSince(any());
    }

    @Test
    void syncAllSkipsResolveWhenFetchEmpty() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchVulnerabilityFindings(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchConfigurationFindings(anyInt())).thenReturn(Collections.emptyList());

        service.syncAll();

        verify(vulnRepo, never()).markResolvedNotSyncedSince(any());
        verify(postureRepo, never()).markResolvedNotSyncedSince(any());
    }
}
