package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.automation.workload.api.HpaApplyService;
import com.xammer.cloud.service.automation.workload.api.HpaAutomationController;
import com.xammer.cloud.service.automation.workload.api.HpaHistoryService;
import com.xammer.cloud.service.automation.workload.api.VerticalApplyService;
import com.xammer.cloud.service.automation.workload.api.dto.HpaApplyResponseDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HPA controller's apply + create contracts: the account-id rule, tenancy, the legacy
 * cluster-config lookup, and every refusal rendered as a typed body — never a bare 403.
 */
class HpaAutomationControllerTest {

    private static final String AWS_ID = "102227938259";
    private static final String FOREIGN_ID = "999999999999";
    private static final String BASE = "/api/eks/automation/hpa";
    private static final String QUERY = "?accountId=" + AWS_ID + "&region=ap-south-1";
    private static final String APPLY = "/xamops-test-cluster/team-b/php-apache/apply";
    private static final String CREATE = "/xamops-test-cluster/team-b/Deployment/php-apache/create";
    private static final String APPLY_BODY = "{\"maxReplicas\":8,\"dryRun\":true}";

    private CloudAccountAccessService accessService;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private HpaApplyService applyService;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        accessService = mock(CloudAccountAccessService.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        applyService = mock(HpaApplyService.class);

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
    @DisplayName("an accepted apply is 202 with correlationId, state, dryRun and the applied bounds")
    void anAcceptedApplyIsA202() throws Exception {
        givenClusterConfigByPrimaryKey();
        when(applyService.apply(any(), any(), any(), any(), any())).thenReturn(
                HpaApplyResponseDto.accepted("c0ffee", "ACCEPTED", true, null, 8, 69));

        mvc().perform(post(BASE + APPLY + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.correlationId").value("c0ffee"))
                .andExpect(jsonPath("$.state").value("ACCEPTED"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.applied.maxReplicas").value(8))
                .andExpect(jsonPath("$.applied.targetUtilizationPct").value(69));
    }

    @Test
    @DisplayName("every 403 gate answers with its exact contract code in the body, not a bare 403")
    void everyGateAnswersWithItsContractCode() throws Exception {
        givenClusterConfigByPrimaryKey();
        for (String code : List.of("K8S_NO_USER_PERMISSION", "K8S_READ_ONLY_CONNECTION",
                "K8S_CONTROLLER_NOT_READY", "K8S_CLUSTER_ACCESS_NOT_CONFIGURED",
                "K8S_CLUSTER_UNREACHABLE")) {
            doThrow(new VerticalApplyService.ApplyRefusedException(HttpStatus.FORBIDDEN, code, null,
                    "Refused because " + code + "."))
                    .when(applyService).apply(any(), any(), any(), any(), any());

            mvc().perform(post(BASE + APPLY + QUERY)
                            .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.field").doesNotExist());
        }
    }

    @Test
    @DisplayName("a safety-rule refusal is a 400 K8S_INVALID_POLICY naming the field and the rule")
    void aSafetyRefusalCarriesCodeFieldAndRule() throws Exception {
        givenClusterConfigByPrimaryKey();
        doThrow(new VerticalApplyService.ApplyRefusedException(HttpStatus.BAD_REQUEST,
                "K8S_INVALID_POLICY", "maxReplicas", "PDB_FLOOR_BINDS: ...", "PDB_FLOOR_BINDS"))
                .when(applyService).apply(any(), any(), any(), any(), any());

        mvc().perform(post(BASE + APPLY + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("K8S_INVALID_POLICY"))
                .andExpect(jsonPath("$.field").value("maxReplicas"))
                .andExpect(jsonPath("$.detail").value("PDB_FLOOR_BINDS"));
    }

    @Test
    @DisplayName("a missing dryRun is a 400 K8S_INVALID_REQUEST naming dryRun")
    void aMissingDryRunIsA400() throws Exception {
        givenClusterConfigByPrimaryKey();
        doThrow(new VerticalApplyService.ApplyRefusedException(HttpStatus.BAD_REQUEST,
                "K8S_INVALID_REQUEST", "dryRun", "dryRun is required on every apply."))
                .when(applyService).apply(any(), any(), any(), any(), any());

        mvc().perform(post(BASE + APPLY + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxReplicas\":8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("K8S_INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("dryRun"));
    }

    @Test
    @DisplayName("the create endpoint answers a typed CONTROLLER_NOT_READY, not a bare 403")
    void createIsRecommendOnly() throws Exception {
        givenClusterConfigByPrimaryKey();

        mvc().perform(post(BASE + CREATE + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("K8S_CONTROLLER_NOT_READY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("recommend-only")));

        verifyNoInteractions(applyService);
    }

    @Test
    @DisplayName("a malformed namespace or name is 400 before any lookup or cluster contact")
    void malformedIdentifiersAreRejectedFirst() throws Exception {
        mvc().perform(post(BASE + "/xamops-test-cluster/Team_B/php-apache/apply" + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("namespace"));

        verifyNoInteractions(clusterConfigRepository, applyService);
    }

    @Test
    @DisplayName("another tenant cannot apply, and no cluster lookup happens")
    void anotherTenantCannotApply() throws Exception {
        mvc().perform(post(BASE + "/xamops-test-cluster/team-b/php-apache/apply"
                        + "?accountId=" + FOREIGN_ID + "&region=ap-south-1")
                        .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clusterConfigRepository, applyService);
    }

    @Test
    @DisplayName("applying to an unregistered cluster is 404 K8S_CLUSTER_NOT_REGISTERED")
    void applyingToAnUnregisteredClusterIsNotFound() throws Exception {
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(any(), any()))
                .thenReturn(Optional.empty());

        mvc().perform(post(BASE + APPLY + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("K8S_CLUSTER_NOT_REGISTERED"));

        verifyNoInteractions(applyService);
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
        HpaAutomationController controller = new HpaAutomationController(
                accessService, clusterConfigRepository, applyService, mock(HpaHistoryService.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }
}
