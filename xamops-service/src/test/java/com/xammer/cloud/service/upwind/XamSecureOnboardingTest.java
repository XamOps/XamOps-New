package com.xammer.cloud.service.upwind;

import com.xammer.cloud.controller.XamSecureOnboardingController;
import com.xammer.cloud.domain.UpwindAccountConnection;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XamSecureOnboardingTest {

    private UpwindAccountConnectionRepository connectionRepo;
    private XamSecureService xamSecureService;
    private XamSecureOnboardingController controller;

    @BeforeEach
    void setUp() {
        connectionRepo = mock(UpwindAccountConnectionRepository.class);
        xamSecureService = mock(XamSecureService.class);
        controller = new XamSecureOnboardingController(xamSecureService, connectionRepo,
                mock(UpwindApiClient.class), mock(UpwindOnboardingService.class));
    }

    @Test
    void registerCreatesConnectionWhenMissing() {
        when(connectionRepo.findByCloudAccountId("111")).thenReturn(Optional.empty());

        controller.register(Map.of(
                "cloudAccountId", "111",
                "status", "CONNECTED",
                "sensorInstallCommand", "helm install upwind-sensor ..."));

        ArgumentCaptor<UpwindAccountConnection> captor =
                ArgumentCaptor.forClass(UpwindAccountConnection.class);
        verify(connectionRepo).save(captor.capture());
        assertEquals("111", captor.getValue().getCloudAccountId());
        assertEquals("CONNECTED", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getUpdatedAt());
    }

    @Test
    void registerUpdatesExistingConnection() {
        UpwindAccountConnection existing = new UpwindAccountConnection();
        existing.setId(4L);
        existing.setCloudAccountId("111");
        existing.setStatus("PENDING");
        when(connectionRepo.findByCloudAccountId("111")).thenReturn(Optional.of(existing));

        controller.register(Map.of("cloudAccountId", "111", "status", "CONNECTED"));

        ArgumentCaptor<UpwindAccountConnection> captor =
                ArgumentCaptor.forClass(UpwindAccountConnection.class);
        verify(connectionRepo).save(captor.capture());
        assertEquals(4L, captor.getValue().getId());
        assertEquals("CONNECTED", captor.getValue().getStatus());
    }

    @Test
    void registerRejectsMissingAccountId() {
        var resp = controller.register(Map.of("status", "CONNECTED"));
        assertEquals(400, resp.getStatusCodeValue());
        verify(connectionRepo, never()).save(any());
    }
}
