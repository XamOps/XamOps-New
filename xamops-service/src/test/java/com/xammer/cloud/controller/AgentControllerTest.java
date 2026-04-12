package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.DbAgentService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class AgentControllerTest {

    private SecurityContext securityContext;
    private Authentication authentication;
    private ClientUserDetails userDetails;

    @BeforeEach
    public void setupSecurity() {
        securityContext = mock(SecurityContext.class);
        authentication = mock(Authentication.class);
        userDetails = mock(ClientUserDetails.class);

        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(userDetails);
    }

    @Test
    public void testLoadDbAgentConfig_Authorized() {
        // Mock dependencies
        AgentIngestService agentIngestService = mock(AgentIngestService.class);
        DbAgentService dbAgentService = mock(DbAgentService.class);
        KubernetesClusterConfigRepository clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        AgentDataCacheRepository agentDataCacheRepository = mock(AgentDataCacheRepository.class);
        AgentEventRepository agentEventRepository = mock(AgentEventRepository.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);

        // Instantiate controller with mocks
        AgentController agentController = new AgentController(
                agentIngestService,
                dbAgentService,
                clusterConfigRepository,
                agentDataCacheRepository,
                agentEventRepository,
                redisCacheService,
                cloudAccountRepository
        );

        // Mock User Details
        when(userDetails.getClientId()).thenReturn(1L);

        // Mock Cloud Account
        CloudAccount account = mock(CloudAccount.class);
        com.xammer.cloud.domain.Client client = mock(com.xammer.cloud.domain.Client.class);
        when(account.getClient()).thenReturn(client);
        when(client.getId()).thenReturn(1L);

        when(dbAgentService.resolveAccount("acc123")).thenReturn(account);

        // Setup mock data
        DbAgentConfig config = new DbAgentConfig();
        config.setDbPassword("secretPassword123");
        config.setDbUser("dbuser");
        config.setDbHost("localhost");

        when(dbAgentService.loadConnectionConfig("acc123", "agentName")).thenReturn(config);

        // Execute
        ResponseEntity<Map<String, Object>> response = agentController.loadDbAgentConfig("agentName", "acc123", userDetails);

        // Verify
        assertEquals(200, response.getStatusCodeValue());
        Map<String, Object> body = response.getBody();
        assertEquals("secretPassword123", body.get("dbPassword"));
    }

    @Test
    public void testLoadDbAgentConfig_Unauthorized() {
        // Mock dependencies
        AgentIngestService agentIngestService = mock(AgentIngestService.class);
        DbAgentService dbAgentService = mock(DbAgentService.class);
        KubernetesClusterConfigRepository clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        AgentDataCacheRepository agentDataCacheRepository = mock(AgentDataCacheRepository.class);
        AgentEventRepository agentEventRepository = mock(AgentEventRepository.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);

        // Instantiate controller with mocks
        AgentController agentController = new AgentController(
                agentIngestService,
                dbAgentService,
                clusterConfigRepository,
                agentDataCacheRepository,
                agentEventRepository,
                redisCacheService,
                cloudAccountRepository
        );

        // Mock User Details
        when(userDetails.getClientId()).thenReturn(1L);

        // Mock Cloud Account (Owned by client 2)
        CloudAccount account = mock(CloudAccount.class);
        com.xammer.cloud.domain.Client client = mock(com.xammer.cloud.domain.Client.class);
        when(account.getClient()).thenReturn(client);
        when(client.getId()).thenReturn(2L);

        when(dbAgentService.resolveAccount("acc123")).thenReturn(account);

        // Execute
        ResponseEntity<Map<String, Object>> response = agentController.loadDbAgentConfig("agentName", "acc123", userDetails);

        // Verify
        assertEquals(403, response.getStatusCodeValue());
    }
}
