package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.controller.XamSecureWebhookController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XamSecureWebhookTest {

    private UpwindSyncService syncService;
    private XamSecureWebhookController controller;

    @BeforeEach
    void setUp() {
        syncService = mock(UpwindSyncService.class);
        controller = new XamSecureWebhookController(syncService, new ObjectMapper());
        controller.setWebhookSecret("s3cr3t");
    }

    @Test
    void rejectsWrongSecret() {
        var resp = controller.ingest("wrong", "{\"id\":\"uwnd-1\"}");
        assertEquals(401, resp.getStatusCode().value());
        verifyNoInteractions(syncService);
    }

    @Test
    void ingestsSingleEvent() {
        var resp = controller.ingest("s3cr3t", "{\"id\":\"uwnd-1\",\"severity\":\"HIGH\"}");
        assertEquals(200, resp.getStatusCode().value());
        verify(syncService, times(1)).ingestEvent(any());
    }

    @Test
    void ingestsArrayOfEvents() {
        var resp = controller.ingest("s3cr3t", "[{\"id\":\"a\"},{\"id\":\"b\"}]");
        assertEquals(200, resp.getStatusCode().value());
        verify(syncService, times(2)).ingestEvent(any());
    }

    @Test
    void missingSecretConfigRejects() {
        controller.setWebhookSecret("");
        var resp = controller.ingest("anything", "{\"id\":\"x\"}");
        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void acceptsSecretViaAuthorizationBearerHeader() {
        var resp = controller.ingest(null, "Bearer s3cr3t", "{\"id\":\"uwnd-1\"}");
        assertEquals(200, resp.getStatusCode().value());
        verify(syncService, times(1)).ingestEvent(any());
    }
}
