package com.xammer.cloud.controller;

import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.dbops.AccessGraphDoc;
import com.xammer.cloud.dto.dbops.DbHealthView;
import com.xammer.cloud.dto.dbops.FkGraphDoc;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbHealthAlertRepository;
import com.xammer.cloud.repository.DbHealthSnapshotRepository;
import com.xammer.cloud.repository.ExplainRequestRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.AccessGraphService;
import com.xammer.cloud.service.DbHealthConsoleService;
import com.xammer.cloud.service.DbOpsCostService;
import com.xammer.cloud.service.FkGraphService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * SEC-1 cross-tenant + SEC-9 intra-tenant proof for the five Phase-2 console endpoints. The REAL
 * {@link CloudAccountAccessService} runs (repos mocked) so the entitlement decisions are production
 * ones. The console flag is ON here so the guards actually run (the flag-off fail-closed path is
 * covered by {@link DbOpsFeatureFlagGateTest}). Assertions are on HTTP status only.
 */
class DbOpsConsoleControllerSecurityTest {

    private static final String AWS_ID = "111111111111";
    private static final String GCP_ID = "my-gcp-project";

    private CloudAccountRepository cloudAccountRepository;
    private DbAgentConfigRepository dbAgentConfigRepository;
    private DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private UserRepository userRepository;
    private UserCloudAccountAccessRepository userAccessRepository;
    private MockMvc mvc;

    /** Root user of client 10 (ROLE_XAMOPS — a paying customer owner, not platform staff). */
    private final ClientUserDetails owner = new ClientUserDetails("owner@example.com", "pw",
            List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 10L, 500L, "tenant-a");
    /** Sub-user of client 10, explicitly granted the AWS account but NOT the primary user. */
    private final ClientUserDetails subUser = new ClientUserDetails("sub@example.com", "pw",
            List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 10L, 501L, "tenant-a");
    /** Root user of client 99 — owns neither account. */
    private final ClientUserDetails outsider = new ClientUserDetails("outsider@example.com", "pw",
            List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 99L, 600L, "tenant-b");

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        dbAgentConfigRepository = mock(DbAgentConfigRepository.class);
        dbAgentDataCacheRepository = mock(DbAgentDataCacheRepository.class);
        userRepository = mock(UserRepository.class);
        userAccessRepository = mock(UserCloudAccountAccessRepository.class);

        Client client10 = new Client();
        client10.setId(10L);

        CloudAccount awsAccount = new CloudAccount();
        awsAccount.setId(1L);
        awsAccount.setProvider("AWS");
        awsAccount.setAwsAccountId(AWS_ID);
        awsAccount.setClient(client10);

        CloudAccount gcpAccount = new CloudAccount();
        gcpAccount.setId(2L);
        gcpAccount.setProvider("GCP");
        gcpAccount.setGcpProjectId(GCP_ID);
        gcpAccount.setClient(client10);

        when(cloudAccountRepository.findByProviderAccountId(AWS_ID)).thenReturn(Optional.of(awsAccount));
        when(cloudAccountRepository.findByProviderAccountId(GCP_ID)).thenReturn(Optional.of(gcpAccount));
        when(cloudAccountRepository.findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(AWS_ID, AWS_ID, AWS_ID))
                .thenReturn(Optional.of(awsAccount));
        when(cloudAccountRepository.findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(GCP_ID, GCP_ID, GCP_ID))
                .thenReturn(Optional.of(gcpAccount));

        // Root users (no createdBy); sub-user carries a createdBy so requirePrimaryUser refuses it.
        when(userRepository.findById(500L)).thenReturn(Optional.of(new User()));
        when(userRepository.findById(600L)).thenReturn(Optional.of(new User()));
        User sub = new User();
        sub.setCreatedBy(500L);
        when(userRepository.findById(501L)).thenReturn(Optional.of(sub));
        when(userAccessRepository.findCloudAccountIdsByUserId(anyLong())).thenReturn(List.of());
        // The sub-user is explicitly granted the AWS account, so it clears the cross-tenant guard
        // and only requirePrimaryUser can refuse it on a sensitive endpoint.
        when(userAccessRepository.findCloudAccountIdsByUserId(501L)).thenReturn(List.of(1L));

        when(dbAgentConfigRepository.findByCloudAccountId(anyLong())).thenReturn(List.of());

        DbHealthConsoleService health = mock(DbHealthConsoleService.class);
        when(health.buildForAccount(any(), any())).thenReturn(DbHealthView.empty("ok"));
        AccessGraphService access = mock(AccessGraphService.class);
        when(access.buildForAccount(any(), any(), any())).thenReturn(AccessGraphDoc.empty());
        FkGraphService fk = mock(FkGraphService.class);
        when(fk.buildForAccount(any(), any(), any(), any(), any())).thenReturn(FkGraphDoc.empty());

        CloudAccountAccessService accessService = new CloudAccountAccessService(
                userRepository, userAccessRepository, cloudAccountRepository);

        DbOpsController controller = new DbOpsController(mock(DbOpsCostService.class), cloudAccountRepository,
                dbAgentConfigRepository, dbAgentDataCacheRepository,
                mock(ExplainRequestRepository.class), mock(DbHealthSnapshotRepository.class),
                mock(DbHealthAlertRepository.class), accessService,
                new DbOpsFeatureFlags(false, false, false, false, false, false, true), // console ON
                health, access, fk);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(ClientUserDetails principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private int status(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .asyncDispatch(result)).andReturn();
        }
        return result.getResponse().getStatus();
    }

    private static final String[] ALL_ENDPOINTS = {
            "console/health", "console/lock-waits", "console/replication",
            "console/access-graph", "console/table-graph"
    };

    // ── Cross-tenant isolation: one 403 per endpoint ──────────────────────────

    @Test
    void outsiderIs403OnEveryConsoleEndpoint() throws Exception {
        authenticateAs(outsider);
        for (String ep : ALL_ENDPOINTS) {
            assertThat(status(get("/api/dbops/aws/{id}/" + ep, AWS_ID)))
                    .as("endpoint %s", ep).isEqualTo(403);
        }
    }

    // ── Cross-cloud: the guard is requireAccessByIdentifier, not an AWS pin ────

    @Test
    void gcpOwner_reachesConsoleHealthCrossCloud_200() throws Exception {
        authenticateAs(owner);
        assertThat(status(get("/api/dbops/aws/{id}/console/health", GCP_ID))).isEqualTo(200);
    }

    @Test
    void gcpOwner_reachesTableGraphCrossCloud_200() throws Exception {
        authenticateAs(owner);
        assertThat(status(get("/api/dbops/aws/{id}/console/table-graph", GCP_ID))).isEqualTo(200);
    }

    // ── Intra-tenant RBAC (SEC-9 interim): sensitive endpoints need the primary user ──

    @Test
    void primaryOwner_reachesSensitiveEndpoints_200() throws Exception {
        // ROLE_XAMOPS is a customer owner, not platform staff: a platform-admin @PreAuthorize gate
        // would 403 this same owner (design §9.1), which is why the interim is a primary-user check.
        authenticateAs(owner);
        assertThat(status(get("/api/dbops/aws/{id}/console/health", AWS_ID))).isEqualTo(200);
        assertThat(status(get("/api/dbops/aws/{id}/console/access-graph", AWS_ID))).isEqualTo(200);
    }

    @Test
    void grantedSubUser_isRefusedSensitiveEndpoints_403() throws Exception {
        authenticateAs(subUser);
        // The sub-user IS granted the account (clears the cross-tenant guard) but is not the primary
        // user, so requirePrimaryUser refuses the two topology-exposing endpoints.
        assertThat(status(get("/api/dbops/aws/{id}/console/health", AWS_ID))).isEqualTo(403);
        assertThat(status(get("/api/dbops/aws/{id}/console/access-graph", AWS_ID))).isEqualTo(403);
    }

    @Test
    void grantedSubUser_reachesNonSensitiveEndpoints_200() throws Exception {
        authenticateAs(subUser);
        // No requirePrimaryUser on the non-sensitive endpoints — the granted sub-user is admitted.
        assertThat(status(get("/api/dbops/aws/{id}/console/lock-waits", AWS_ID))).isEqualTo(200);
        assertThat(status(get("/api/dbops/aws/{id}/console/replication", AWS_ID))).isEqualTo(200);
        assertThat(status(get("/api/dbops/aws/{id}/console/table-graph", AWS_ID))).isEqualTo(200);
    }

    // ── Resilience: a corrupt cache row for one DSN does not 500 the list endpoint ──

    @Test
    void corruptCacheRow_lockWaits_stillReturns200() throws Exception {
        com.xammer.cloud.domain.DbAgentConfig agent = new com.xammer.cloud.domain.DbAgentConfig();
        agent.setId(77L);
        agent.setCloudAccountId(1L);
        when(dbAgentConfigRepository.findByCloudAccountId(1L)).thenReturn(List.of(agent));
        com.xammer.cloud.domain.DbAgentDataCache corrupt = new com.xammer.cloud.domain.DbAgentDataCache();
        corrupt.setDbAgentId(77L);
        corrupt.setDataType("db-lock-waits:1:db-0");
        corrupt.setDataJson("{ this is not valid json ]");
        when(dbAgentDataCacheRepository.findByDbAgentId(77L)).thenReturn(List.of(corrupt));

        authenticateAs(owner);
        assertThat(status(get("/api/dbops/aws/{id}/console/lock-waits", AWS_ID))).isEqualTo(200);
    }
}
