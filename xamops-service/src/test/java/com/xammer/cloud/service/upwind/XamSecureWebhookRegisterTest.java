package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.controller.XamSecureOnboardingController;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XamSecureWebhookRegisterTest {

    private UpwindApiClient apiClient;
    private XamSecureOnboardingController controller;

    @BeforeEach
    void setUp() {
        apiClient = mock(UpwindApiClient.class);
        var svc = mock(XamSecureService.class);
        var connRepo = mock(UpwindAccountConnectionRepository.class);
        controller = new XamSecureOnboardingController(svc, connRepo, apiClient);
        controller.setWebhookSecret("s3cr3t");
        controller.setPublicBaseUrl("https://xamops.example");
    }

    @Test
    void registerWebhookPostsCustomVendorWithCallbackAndSecret() throws Exception {
        JsonNode created = new ObjectMapper().readTree("{\"id\":\"wh-1\"}");
        when(apiClient.createWebhook(any())).thenReturn(created);

        var resp = controller.registerWebhook();
        assertEquals(200, resp.getStatusCodeValue());

        ArgumentCaptor<JsonNode> cap = ArgumentCaptor.forClass(JsonNode.class);
        verify(apiClient).createWebhook(cap.capture());
        JsonNode body = cap.getValue();
        assertEquals("CUSTOM", body.get("vendor").asText());
        assertTrue(body.get("config").get("url").asText().contains("/api/xamsecure/webhook/upwind"));
        assertEquals("s3cr3t", body.get("config").get("authentication").get("token").asText());
    }

    @Test
    void registerWebhookFailsWithoutSecret() {
        controller.setWebhookSecret("");
        var resp = controller.registerWebhook();
        assertEquals(400, resp.getStatusCodeValue());
        verifyNoInteractions(apiClient);
    }
}
