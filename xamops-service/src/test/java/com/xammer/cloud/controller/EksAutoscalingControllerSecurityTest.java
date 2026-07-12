package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.EksAutoscalingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security reproduction test for EksAutoscalingController.
 *
 * This test demonstrates that the controller currently accepts any accountId
 * without validating ownership, which leads to IDOR (Insecure Direct Object Reference).
 */
class EksAutoscalingControllerSecurityTest {

    private MockMvc mockMvc;

    @Mock
    private EksAutoscalingService autoscalingService;

    @Mock
    private KubernetesClusterConfigRepository clusterConfigRepository;

    @Mock
    private CloudAccountRepository cloudAccountRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        EksAutoscalingController controller = new EksAutoscalingController(
                autoscalingService, clusterConfigRepository, cloudAccountRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.clearContext();
    }

    @Test
    void getDashboard_ShouldFailWhenNotAuthenticated() throws Exception {
        // No authentication in context
        mockMvc.perform(get("/api/eks/autoscaling/my-cluster")
                        .param("accountId", "99999")
                        .param("region", "us-east-1"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void getDashboard_ShouldFailWhenUserDoesNotOwnAccount_IDOR() throws Exception {
        // User belongs to clientId 1
        ClientUserDetails userDetails = new ClientUserDetails(
                "user", "pass", List.of(new SimpleGrantedAuthority("ROLE_USER")),
                1L, 100L, "tenant1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

        // Requested accountId 99999 is NOT owned by clientId 1
        when(cloudAccountRepository.findByAwsAccountIdAndClientId(eq("000000099999"), eq(1L)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/eks/autoscaling/my-cluster")
                        .param("accountId", "99999")
                        .param("region", "us-east-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getDashboard_ShouldSucceedWhenUserOwnsAccount() throws Exception {
        // User belongs to clientId 1
        ClientUserDetails userDetails = new ClientUserDetails(
                "user", "pass", List.of(new SimpleGrantedAuthority("ROLE_USER")),
                1L, 100L, "tenant1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

        // Requested accountId 12345 IS owned by clientId 1
        CloudAccount account = new CloudAccount();
        account.setId(1L);
        account.setAwsAccountId("000000012345");
        Client client = new Client();
        client.setId(1L);
        account.setClient(client);

        when(cloudAccountRepository.findByAwsAccountIdAndClientId(eq("000000012345"), eq(1L)))
                .thenReturn(Optional.of(account));

        mockMvc.perform(get("/api/eks/autoscaling/my-cluster")
                        .param("accountId", "12345")
                        .param("region", "us-east-1"))
                .andExpect(status().isNotFound()); // NotFound from service because no config exists, but passed ownership check
    }

    @Test
    void getDashboard_ShouldSucceedForSuperAdminEvenOtherAccount() throws Exception {
        // User is ROLE_SUPER_ADMIN
        ClientUserDetails adminDetails = new ClientUserDetails(
                "admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")),
                0L, 1L, "system");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminDetails, null, adminDetails.getAuthorities()));

        // SuperAdmin can access any account, no DB check needed in validateAccountOwnership

        mockMvc.perform(get("/api/eks/autoscaling/my-cluster")
                        .param("accountId", "99999")
                        .param("region", "us-east-1"))
                .andExpect(status().isNotFound()); // NotFound from service, ownership check bypassed for SuperAdmin
    }
}
