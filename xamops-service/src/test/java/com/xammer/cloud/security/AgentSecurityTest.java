package com.xammer.cloud.security;

import com.xammer.cloud.controller.AgentController;
import com.xammer.cloud.service.*;
import com.xammer.cloud.service.sre.SreInvestigationService;
import com.xammer.cloud.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
public class AgentSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private AgentIngestService agentIngestService;
    @MockBean private DbAgentService dbAgentService;
    @MockBean private KubernetesClusterConfigRepository clusterConfigRepository;
    @MockBean private AgentDataCacheRepository agentDataCacheRepository;
    @MockBean private AgentEventRepository agentEventRepository;
    @MockBean private RedisCacheService redisCacheService;
    @MockBean private CloudAccountRepository cloudAccountRepository;
    @MockBean private SreInvestigationService sreInvestigationService;
    @MockBean private OpenObserveService openObserveService;
    @MockBean private ExplainRequestRepository explainRequestRepository;

    // Mocks to satisfy DataInitializer and DatabaseInitializer picked up by component scan
    @MockBean private ClientRepository clientRepository;
    @MockBean private UserRepository userRepository;
    @MockBean private PasswordEncoder passwordEncoder;
    @MockBean private DevOpsScriptRepository devOpsScriptRepository;
    @MockBean private JdbcTemplate jdbcTemplate;

    @Test
    public void installInfo_requiresAuthentication() throws Exception {
        // Verify that the management endpoint is now secured
        mockMvc.perform(get("/api/v1/agent/install-info"))
                .andExpect(status().isUnauthorized());
    }
}
