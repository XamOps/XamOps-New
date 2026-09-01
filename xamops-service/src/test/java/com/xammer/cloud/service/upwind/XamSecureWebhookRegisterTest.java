package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.controller.XamSecureOnboardingController;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class XamSecureWebhookRegisterTest {

    private UpwindApiClient apiClient;
    private XamSecureOnboardingController controller;

    @BeforeEach
    void setUp() {
        apiClient = mock(UpwindApiClient.class);
        var svc = mock(XamSecureService.class);
        var connRepo = mock(UpwindAccountConnectionRepository.class);
        controller = new XamSecureOnboardingController(svc, connRepo, apiClient,
                mock(UpwindOnboardingService.class));
        controller.setWebhookSecret("s3cr3t");
        controller.setPublicBaseUrl("https://xamops.example");
    }

    /**
     * The v2 contract uses lowercase enum values ({@code vendor: custom},
     * {@code authentication.type: bearer}); the previous v1 body sent them uppercase.
     */
    @Test
    void registerWebhookPostsCustomVendorWithCallbackAndSecret() throws Exception {
        JsonNode created = new ObjectMapper().readTree("{\"id\":\"wh-1\"}");
        when(apiClient.searchWebhooks(anyInt())).thenReturn(java.util.List.of());
        when(apiClient.createWebhook(any())).thenReturn(created);

        var resp = controller.registerWebhook();
        assertEquals(200, resp.getStatusCode().value());

        ArgumentCaptor<JsonNode> cap = ArgumentCaptor.forClass(JsonNode.class);
        verify(apiClient).createWebhook(cap.capture());
        JsonNode body = cap.getValue();
        assertEquals("custom", body.get("vendor").asText());
        assertTrue(body.get("config").get("url").asText().contains("/api/xamsecure/webhook/upwind"));
        assertEquals("bearer", body.get("config").get("authentication").get("type").asText());
        assertEquals("s3cr3t", body.get("config").get("authentication").get("token").asText());
    }

    /** Re-running registration must not create a second webhook delivering the same events. */
    @Test
    void registerWebhookIsIdempotentWhenAlreadyRegistered() throws Exception {
        JsonNode existing = new ObjectMapper().readTree(
                "{\"id\":\"wh-existing\",\"name\":\"" + XamSecureOnboardingController.WEBHOOK_NAME + "\"}");
        when(apiClient.searchWebhooks(anyInt())).thenReturn(java.util.List.of(existing));

        var resp = controller.registerWebhook();

        assertEquals(200, resp.getStatusCode().value());
        verify(apiClient, never()).createWebhook(any());
    }

    /** A failed lookup must not block registration — worst case we create a duplicate. */
    @Test
    void registerWebhookStillCreatesWhenSearchFails() throws Exception {
        when(apiClient.searchWebhooks(anyInt())).thenThrow(new IllegalStateException("403"));
        when(apiClient.createWebhook(any()))
                .thenReturn(new ObjectMapper().readTree("{\"id\":\"wh-2\"}"));

        var resp = controller.registerWebhook();

        assertEquals(200, resp.getStatusCode().value());
        verify(apiClient).createWebhook(any());
    }

    @Test
    void registerWebhookFailsWithoutSecret() {
        controller.setWebhookSecret("");
        var resp = controller.registerWebhook();
        assertEquals(400, resp.getStatusCode().value());
        verifyNoInteractions(apiClient);
    }
}
