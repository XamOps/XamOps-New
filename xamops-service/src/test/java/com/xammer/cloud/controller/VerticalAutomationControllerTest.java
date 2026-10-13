package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.api.VerticalApplyService;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationController;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationReadService;
import com.xammer.cloud.service.automation.workload.api.VerticalReadServiceFixtures;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyResponseDto;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import com.xammer.cloud.service.automation.workload.usage.NoMetricsSourceProvider;
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
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Both endpoints' contracts: the 12-digit account-id rule, tenancy, the legacy cluster-config
 * lookup, the gated empty payload, and every refusal code the apply endpoint can answer with.
 * Everything is mocked, so nothing reaches AWS or a cluster.
 *
 * <p>The apply cases assert the <strong>body</strong>, not just the status: a 403 with no
 * {@code code} is indistinguishable from a crash to the UI, which disables a control and explains
 * why from exactly that field.
 */
class VerticalAutomationControllerTest {

    private static final String AWS_ID = "102227938259";
    private static final String FOREIGN_ID = "999999999999";
    private static final String BASE = "/api/eks/automation/vertical";
    private static final String QUERY = "?accountId=" + AWS_ID + "&region=ap-south-1";

    private CloudAccountAccessService accessService;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private VerticalApplyService applyService;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        accessService = mock(CloudAccountAccessService.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        applyService = mock(VerticalApplyService.class);

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
        when(applyService.callerWriteGates(any(), any()))
                .thenReturn(new VerticalApplyService.WriteGates(List.of(), Map.of()));

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
    @DisplayName("the caller's write gates are reported after the engine's own, from endpoint 3's predicates")
    void theCallersWriteGatesAreReported() throws Exception {
        givenClusterConfigByPrimaryKey();
        when(applyService.callerWriteGates(any(), eq(account)))
                .thenReturn(new VerticalApplyService.WriteGates(
                        List.of("NO_USER_PERMISSION", "READ_ONLY_CONNECTION"),
                        Map.of("NO_USER_PERMISSION", "TAB_GRANT")));

        mvc().perform(get(BASE + "/xamops-test-cluster" + QUERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateReasons[0]").value("CONTROLLER_NOT_READY"))
                .andExpect(jsonPath("$.gateReasons")
                        .value(org.hamcrest.Matchers.hasItems("NO_USER_PERMISSION", "READ_ONLY_CONNECTION")))
                .andExpect(jsonPath("$.gateDetails.NO_USER_PERMISSION").value("TAB_GRANT"))
                .andExpect(jsonPath("$.writeGatesChecked").value(true));
    }

    @Test
    @DisplayName("a permitted caller's payload still says its write gates were checked")
    void aPermittedCallerIsCheckedNotJustUngated() throws Exception {
        // Absence of a gate used to be the only signal. A build that never evaluated the gates
        // produced the same empty list, and a frontend deployed ahead of its backend enabled an
        // Apply the server refused. writeGatesChecked is the positive statement.
        givenClusterConfigByPrimaryKey();

        mvc().perform(get(BASE + "/xamops-test-cluster" + QUERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writeGatesChecked").value(true))
                .andExpect(jsonPath("$.gateDetails").isMap())
                .andExpect(jsonPath("$.gateDetails").isEmpty())
                .andExpect(jsonPath("$.gateReasons")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("NO_USER_PERMISSION"))));
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

    // ------------------------------------------------------------------ endpoint 3, apply

    private static final String APPLY = "/xamops-test-cluster/workloads/team-a/Deployment/"
            + "overprovisioned-api/apply";
    private static final String APPLY_BODY = """
            {"containers":[{"name":"api","cpuRequestM":25,"memRequestMi":250,\
            "cpuLimitM":25,"memLimitMi":250}],"applyMethod":"IN_PLACE","dryRun":true}""";

    @Test
    @DisplayName("an accepted apply is 202 with correlationId, state, dryRun and the real result")
    void anAcceptedApplyIsA202WithItsOutcome() throws Exception {
        givenClusterConfigByPrimaryKey();
        when(applyService.apply(any(), any(), any(), any(), any())).thenReturn(
                new ApplyResponseDto("c0ffee", "ACCEPTED", true,
                        new ApplyResponseDto.Result("IN_PLACE", "APPLIED", null, true,
                                List.of(new ApplyResponseDto.ContainerResultDto("api", "APPLIED", null,
                                        new VerticalWorkloadDto.ResourcesDto(25, 250L, 25, 250L), false)),
                                List.of(new ApplyResponseDto.PodProofDto("api-1", "uid-1", "uid-1", true,
                                        true, List.of())))));

        mvc().perform(post(BASE + APPLY + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.correlationId").value("c0ffee"))
                .andExpect(jsonPath("$.state").value("ACCEPTED"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.result.terminal").value("APPLIED"))
                .andExpect(jsonPath("$.result.restartFree").value(true))
                .andExpect(jsonPath("$.result.pods[0].samePod").value(true))
                .andExpect(jsonPath("$.result.containers[0].state").value("APPLIED"));
    }

    @Test
    @DisplayName("every 403 gate answers with its exact contract code in the body, not a bare 403")
    void everyGateAnswersWithItsContractCode() throws Exception {
        givenClusterConfigByPrimaryKey();
        for (String code : List.of("K8S_NO_USER_PERMISSION", "K8S_READ_ONLY_CONNECTION",
                "K8S_CONTROLLER_NOT_READY", "K8S_CLUSTER_ACCESS_NOT_CONFIGURED",
                "K8S_CLUSTER_UNREACHABLE", "K8S_GITOPS_MANAGED")) {
            // doThrow, not when(...).thenThrow: re-stubbing through when() would invoke the
            // already-stubbed method and throw the previous loop's exception.
            doThrow(new VerticalApplyService.ApplyRefusedException(HttpStatus.FORBIDDEN, code, null,
                    "Refused because " + code + "."))
                    .when(applyService).apply(any(), any(), any(), any(), any());

            mvc().perform(post(BASE + APPLY + QUERY)
                            .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.message").value("Refused because " + code + "."))
                    .andExpect(jsonPath("$.field").doesNotExist())
                    .andExpect(jsonPath("$.detail").doesNotExist());
        }
    }

    @Test
    @DisplayName("a permission refusal names its rule in detail, so the UI can say which remedy applies")
    void aPermissionRefusalCarriesItsDetail() throws Exception {
        givenClusterConfigByPrimaryKey();
        for (String detail : List.of("TAB_GRANT", "IMPERSONATION", "ROLE", "NO_PRINCIPAL")) {
            doThrow(new VerticalApplyService.ApplyRefusedException(HttpStatus.FORBIDDEN,
                    "K8S_NO_USER_PERMISSION", null, "Refused by " + detail + ".", detail))
                    .when(applyService).apply(any(), any(), any(), any(), any());

            mvc().perform(post(BASE + APPLY + QUERY)
                            .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("K8S_NO_USER_PERMISSION"))
                    .andExpect(jsonPath("$.detail").value(detail))
                    .andExpect(jsonPath("$.field").doesNotExist());
        }
    }

    @Test
    @DisplayName("a 400 K8S_INVALID_POLICY names the offending field, including a missing dryRun")
    void aBadBodyIsA400NamingItsField() throws Exception {
        givenClusterConfigByPrimaryKey();
        doThrow(new VerticalApplyService.ApplyRefusedException(HttpStatus.BAD_REQUEST,
                "K8S_INVALID_POLICY", "dryRun", "dryRun is required on every apply."))
                .when(applyService).apply(any(), any(), any(), any(), any());

        mvc().perform(post(BASE + APPLY + QUERY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"containers\":[{\"name\":\"api\",\"cpuRequestM\":25}],"
                                + "\"applyMethod\":\"IN_PLACE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("K8S_INVALID_POLICY"))
                .andExpect(jsonPath("$.field").value("dryRun"));
    }

    @Test
    @DisplayName("a 401 refusal from the gate is a 401 body, not a redirect")
    void anUnauthenticatedApplyIsA401Body() throws Exception {
        givenClusterConfigByPrimaryKey();
        doThrow(new VerticalApplyService.ApplyRefusedException(HttpStatus.UNAUTHORIZED,
                "K8S_UNAUTHENTICATED", null, "Sign in again to apply automation changes."))
                .when(applyService).apply(any(), any(), any(), any(), any());

        mvc().perform(post(BASE + APPLY + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("K8S_UNAUTHENTICATED"));
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

    @Test
    @DisplayName("a malformed namespace, kind or name is 400 before any lookup or cluster contact")
    void malformedWorkloadIdentifiersAreRejectedFirst() throws Exception {
        mvc().perform(post(BASE + "/xamops-test-cluster/workloads/Team_A/Deployment/api/apply" + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("namespace"));

        mvc().perform(post(BASE + "/xamops-test-cluster/workloads/team-a/Deploy-ment/api/apply" + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("kind"));

        verifyNoInteractions(clusterConfigRepository, applyService);
    }

    @Test
    @DisplayName("another tenant cannot apply, and no cluster lookup happens")
    void anotherTenantCannotApply() throws Exception {
        mvc().perform(post(BASE + "/xamops-test-cluster/workloads/team-a/Deployment/api/apply"
                        + "?accountId=" + FOREIGN_ID + "&region=ap-south-1")
                        .contentType(MediaType.APPLICATION_JSON).content(APPLY_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clusterConfigRepository, applyService);
    }

    // ------------------------------------------------------------------ endpoint 2, mode

    private static final String POLICY = "/xamops-test-cluster/workloads/team-a/Deployment/"
            + "overprovisioned-api/policy";

    @Test
    @DisplayName("a saved mode is 200 echoing the mode and policySource the server stored")
    void aSavedModeIsA200WithTheStoredState() throws Exception {
        givenClusterConfigByPrimaryKey();
        when(applyService.setMode(any(), any(), any(), any(), eq("AUTO")))
                .thenReturn(new com.xammer.cloud.service.automation.workload.api.dto.PolicyResponseDto(
                        "AUTO", "WORKLOAD_ROW"));

        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(BASE + POLICY + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"AUTO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("AUTO"))
                .andExpect(jsonPath("$.policySource").value("WORKLOAD_ROW"));
    }

    @Test
    @DisplayName("a mode change runs the apply gates: every 403 answers with its exact contract code")
    void aModeChangeIsGatedLikeAnApply() throws Exception {
        givenClusterConfigByPrimaryKey();
        for (String code : List.of("K8S_NO_USER_PERMISSION", "K8S_READ_ONLY_CONNECTION",
                "K8S_CONTROLLER_NOT_READY", "K8S_DEMO")) {
            doThrow(new VerticalApplyService.ApplyRefusedException(HttpStatus.FORBIDDEN, code, null,
                    "Refused because " + code + "."))
                    .when(applyService).setMode(any(), any(), any(), any(), any());

            mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put(BASE + POLICY + QUERY)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"AUTO\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.field").doesNotExist());
        }
    }

    @Test
    @DisplayName("an invalid mode is 400 K8S_INVALID_POLICY naming the mode field")
    void anInvalidModeIsA400NamingTheField() throws Exception {
        givenClusterConfigByPrimaryKey();
        doThrow(new VerticalApplyService.ApplyRefusedException(HttpStatus.BAD_REQUEST,
                "K8S_INVALID_POLICY", "mode", "mode must be OFF, RECOMMEND or AUTO, got 'ON'."))
                .when(applyService).setMode(any(), any(), any(), any(), any());

        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(BASE + POLICY + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ON\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("K8S_INVALID_POLICY"))
                .andExpect(jsonPath("$.field").value("mode"));
    }

    @Test
    @DisplayName("a mode change to an unregistered cluster is 404 K8S_CLUSTER_NOT_REGISTERED")
    void aModeChangeToAnUnregisteredClusterIsNotFound() throws Exception {
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(any(), any()))
                .thenReturn(Optional.empty());

        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(BASE + POLICY + QUERY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"AUTO\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("K8S_CLUSTER_NOT_REGISTERED"));

        verifyNoInteractions(applyService);
    }

    @Test
    @DisplayName("another tenant cannot change a mode, and no cluster lookup happens")
    void anotherTenantCannotChangeMode() throws Exception {
        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(BASE + "/xamops-test-cluster/workloads/team-a/Deployment/api/policy"
                                + "?accountId=" + FOREIGN_ID + "&region=ap-south-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"AUTO\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clusterConfigRepository, applyService);
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
        VerticalAutomationController controller = new VerticalAutomationController(
                accessService, clusterConfigRepository, readService, applyService);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }
}
