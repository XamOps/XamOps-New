package com.xammer.cloud.security;

import com.xammer.cloud.controller.AgentController;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.OpenObserveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Partial security verification for AgentController endpoints.
 * Since we can't easily test the full SecurityFilterChain in a unit test without
 * loading the full context, we verify that the controller itself correctly
 * enforces API key checks for whitelisted endpoints.
 *
 * The SecurityConfig.java changes (removing the wildcard) ensure that non-whitelisted
 * endpoints like /install-info are no longer public at the network level.
 */
class AgentSecurityTest {

    private MockMvc mockMvc;

    @Mock
    private AgentIngestService agentIngestService;

    @Mock
    private OpenObserveService openObserveService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        AgentController controller = new AgentController(
                agentIngestService, null, null, null, null, null, null, null, openObserveService, null);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(agentIngestService.validateApiKey(anyString())).thenReturn(false);
        when(agentIngestService.validateApiKey("valid-key")).thenReturn(true);
        when(agentIngestService.getGlobalApiKey()).thenReturn("valid-key");

        when(openObserveService.getAgentConfig(anyString())).thenReturn(Map.of("test", "config"));
    }

    @Test
    void configEndpoint_requiresValidApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/agent/config")
                .header("Authorization", "Bearer invalid-key"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/agent/config")
                .header("Authorization", "Bearer valid-key"))
                .andExpect(status().isOk());
    }

    @Test
    void commandsEndpoint_requiresValidApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/agent/commands")
                .param("agentName", "test-agent")
                .header("Authorization", "Bearer invalid-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void installInfoEndpoint_doesNotCheckApiKeyInternaly() throws Exception {
        // This endpoint does NOT check the API key internally.
        // Previously, it was public due to the SecurityConfig wildcard.
        // Now, SecurityConfig requires a session for it.
        // This test confirms that it returns 200 even WITHOUT an API key header
        // if SecurityConfig allows it (which standalone MockMvc does by default).
        mockMvc.perform(get("/api/v1/agent/install-info"))
                .andExpect(status().isOk());
    }
}
