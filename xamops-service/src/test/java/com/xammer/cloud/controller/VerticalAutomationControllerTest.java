package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationController;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationReadService;
import com.xammer.cloud.service.automation.workload.api.VerticalReadServiceFixtures;
import com.xammer.cloud.service.automation.workload.usage.NoMetricsSourceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read endpoint's contract: the 12-digit account-id rule, tenancy, the legacy cluster-config
 * lookup, and the gated empty payload. Everything is mocked, so nothing reaches AWS or a cluster.
 */
class VerticalAutomationControllerTest {

    private static final String AWS_ID = "102227938259";
    private static final String FOREIGN_ID = "999999999999";
    private static final String BASE = "/api/eks/automation/vertical";
    private static final String QUERY = "?accountId=" + AWS_ID + "&region=ap-south-1";

    private CloudAccountAccessService accessService;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        accessService = mock(CloudAccountAccessService.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);

        Client client = new Client();
        client.setId(7L);
        account = new CloudAccount();
        account.setId(3L);
        account.setProvider("AWS");
        account.setAwsAccountId(AWS_ID);
        account.setStatus("CONNECTED");
        account.setClient(client);

        when(accessService.requireAwsAccess(any(), eq(AWS_ID))).thenReturn(account);
        when(accessService.requireAwsAccess(any(), eq(FOREIGN_ID)))
                .thenThrow(new AccessDeniedException("Account not found or access denied."));

        ClientUserDetails principal = new ClientUserDetails("user@example.com", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), 10L, 7L, "tenant-a");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the default gated payload: enabled false, ready false, no workloads")
    void theGatedPayloadIsHonest() throws Exception {
        givenClusterConfigByPrimaryKey();

        mvc().perform(get(BASE + "/xamops-test-cluster" + QUERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engine.enabled").value(false))
                .andExpect(jsonPath("$.engine.ready").value(false))
                .andExpect(jsonPath("$.engine.executionPath").value("NONE"))
                .andExpect(jsonPath("$.engine.metricsSource.reachable").value(false))
                .andExpect(jsonPath("$.engine.lastCycle.at").doesNotExist())
                .andExpect(jsonPath("$.gateReasons").isArray())
                .andExpect(jsonPath("$.workloads").isEmpty());
    }

    @Test
    @DisplayName("another tenant's account is refused, and no cluster lookup happens")
    void anotherTenantIsRefused() throws Exception {
        mvc().perform(get(BASE + "/xamops-test-cluster?accountId=" + FOREIGN_ID + "&region=ap-south-1"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clusterConfigRepository);
    }

    @Test
    @DisplayName("an unregistered cluster is 404 K8S_CLUSTER_NOT_REGISTERED, not an empty payload")
    void anUnregisteredClusterIsNotFound() throws Exception {
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(any(), any()))
                .thenReturn(Optional.empty());

        mvc().perform(get(BASE + "/xamops-test-cluster" + QUERY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("K8S_CLUSTER_NOT_REGISTERED"));
    }

    @Test
    @DisplayName("a legacy cluster config keyed by AWS account number is still found")
    void legacyClusterConfigRowsResolve() throws Exception {
        // kubernetes_cluster_config.cloud_account_id holds the cloud_account primary key on modern
        // rows and the AWS account number on legacy ones. Both candidates come off the ENTITLED
        // row, so neither lookup can reach another tenant's cluster.
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(3L, "xamops-test-cluster"))
                .thenReturn(Optional.empty());
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(102227938259L, "xamops-test-cluster"))
                .thenReturn(Optional.of(clusterConfig()));

        mvc().perform(get(BASE + "/xamops-test-cluster" + QUERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workloads").isEmpty());
    }

    @Test
    @DisplayName("a malformed accountId or cluster name is 400 with the field named")
    void malformedInputIsRejectedBeforeAnyLookup() throws Exception {
        mvc().perform(get(BASE + "/xamops-test-cluster?accountId=not-a-number&region=ap-south-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("K8S_INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("accountId"));

        mvc().perform(get(BASE + "/-bad-cluster-" + QUERY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("cluster"));

        mvc().perform(get(BASE + "/xamops-test-cluster?accountId=" + AWS_ID + "&region=NOT_A_REGION"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("region"));

        verifyNoInteractions(clusterConfigRepository);
    }

    @Test
    @DisplayName("a missing required parameter is a 400, not a 500")
    void missingParametersAreRejected() throws Exception {
        mvc().perform(get(BASE + "/xamops-test-cluster"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a server-side invariant failure is not reported as a malformed parameter")
    void domainInvariantFailuresAreNotBlamedOnTheCaller() throws Exception {
        // ClusterTarget, ContainerDecision and ResourceEnvelope.recommendation all raise
        // IllegalArgumentException for an engine/domain invariant. The controller's 400 handler used
        // to catch that whole family and splice the message into "The {message} parameter is missing
        // or malformed", so a server fault arrived as a 400 blaming the caller, in copy like
        // "The clientId is mandatory: tenant scoping is not optional parameter is missing...".
        givenClusterConfigByPrimaryKey();
        VerticalAutomationReadService exploding = mock(VerticalAutomationReadService.class);
        when(exploding.read(any(), any()))
                .thenThrow(new IllegalArgumentException("clientId is mandatory: tenant scoping is not optional"));

        mvc(exploding).perform(get(BASE + "/xamops-test-cluster" + QUERY))
                .andExpect(jsonPath("$.field").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    private void givenClusterConfigByPrimaryKey() {
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(3L, "xamops-test-cluster"))
                .thenReturn(Optional.of(clusterConfig()));
    }

    private static KubernetesClusterConfig clusterConfig() {
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(42L);
        config.setClusterName("xamops-test-cluster");
        config.setRegion("ap-south-1");
        return config;
    }

    private MockMvc mvc() {
        return mvc(VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(false, false),
                new NoMetricsSourceProvider(), List.of()));
    }

    private MockMvc mvc(VerticalAutomationReadService readService) {
        VerticalAutomationController controller =
                new VerticalAutomationController(accessService, clusterConfigRepository, readService);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }
}
