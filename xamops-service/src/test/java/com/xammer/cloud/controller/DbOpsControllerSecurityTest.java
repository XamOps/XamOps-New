package com.xammer.cloud.controller;

import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.dbops.DbInstanceDto;
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
import com.xammer.cloud.service.DbOpsCostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-1 cross-tenant proof for {@link DbOpsController}, and SEC-2/3's 409 when Explain is off.
 *
 * <p>
 * The REAL {@link CloudAccountAccessService} runs (its repositories are mocked), so the
 * entitlement decision under test is the production one, not a stub. {@link GlobalExceptionHandler}
 * maps the guard's {@code AccessDeniedException} to a fixed-body 403, so every assertion is on
 * HTTP status, never on the message string. One AWS-only endpoint, one deliberately
 * provider-shared (cross-cloud) endpoint, and the Explain handlers are covered.
 */
class DbOpsControllerSecurityTest {

    private static final String AWS_ID = "111111111111";
    private static final String GCP_ID = "my-gcp-project";

    private DbOpsCostService dbOpsCostService;
    private CloudAccountRepository cloudAccountRepository;
    private UserRepository userRepository;
    private UserCloudAccountAccessRepository userAccessRepository;
    private MockMvc mvc;

    /** Root user of client 10, which owns both the AWS and the GCP account below. */
    private final ClientUserDetails owner = new ClientUserDetails("owner@example.com", "pw",
            List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 10L, 500L, "tenant-a");
    /** Root user of client 99, which owns neither. */
    private final ClientUserDetails outsider = new ClientUserDetails("outsider@example.com", "pw",
            List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 99L, 600L, "tenant-b");

    @BeforeEach
    void setUp() {
        dbOpsCostService = mock(DbOpsCostService.class);
        cloudAccountRepository = mock(CloudAccountRepository.class);
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

        when(cloudAccountRepository.findByAwsAccountId(AWS_ID)).thenReturn(List.of(awsAccount));
        when(cloudAccountRepository.findByProviderAccountId(AWS_ID)).thenReturn(Optional.of(awsAccount));
        when(cloudAccountRepository.findByProviderAccountId(GCP_ID)).thenReturn(Optional.of(gcpAccount));

        // Both principals are root users (no createdBy) with no explicit grants, so canAccess
        // falls through to the client-wide rule — exactly how a root user is entitled in prod.
        when(userRepository.findById(500L)).thenReturn(Optional.of(new User()));
        when(userRepository.findById(600L)).thenReturn(Optional.of(new User()));
        when(userAccessRepository.findCloudAccountIdsByUserId(anyLong())).thenReturn(List.of());

        CloudAccountAccessService accessService = new CloudAccountAccessService(
                userRepository, userAccessRepository, cloudAccountRepository);

        DbOpsController controller = new DbOpsController(dbOpsCostService, cloudAccountRepository,
                mock(DbAgentConfigRepository.class), mock(DbAgentDataCacheRepository.class),
                mock(ExplainRequestRepository.class), mock(DbHealthSnapshotRepository.class),
                mock(DbHealthAlertRepository.class), accessService,
                new DbOpsFeatureFlags(false)); // Explain disabled (default)

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        when(dbOpsCostService.getDbInventory(eq(AWS_ID), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(List.<DbInstanceDto>of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(ClientUserDetails principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** Runs the request, dispatching async handlers, and returns the final HTTP status. */
    private int status(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        return result.getResponse().getStatus();
    }

    // ── AWS-only endpoint: requireAwsAccess (provider-pinned) ─────────────────

    @Test
    void awsOnlyInventory_ownerGets200() throws Exception {
        authenticateAs(owner);
        assertThat(status(get("/api/dbops/aws/{id}/inventory", AWS_ID))).isEqualTo(200);
    }

    @Test
    void awsOnlyInventory_outsiderGets403() throws Exception {
        authenticateAs(outsider);
        assertThat(status(get("/api/dbops/aws/{id}/inventory", AWS_ID))).isEqualTo(403);
    }

    @Test
    void awsOnlyInventory_gcpIdentifierGets403_providerPin() throws Exception {
        // Even the entitled owner is refused a GCP id here: requireAwsAccess rejects a non-AWS id.
        authenticateAs(owner);
        assertThat(status(get("/api/dbops/aws/{id}/inventory", GCP_ID))).isEqualTo(403);
    }

    // ── Provider-shared endpoint: requireAccessByIdentifier (cross-cloud) ─────

    @Test
    void providerSharedWaitEvents_gcpOwnerGets200_crossCloud() throws Exception {
        // The GCP DataOps drawer reaches this AWS endpoint with a GCP id; the entitled owner of
        // the GCP account must get 200, not a provider-pin 403 (design M-1).
        authenticateAs(owner);
        assertThat(status(get("/api/dbops/aws/{id}/agent-wait-events", GCP_ID))).isEqualTo(200);
    }

    @Test
    void providerSharedWaitEvents_outsiderGets403() throws Exception {
        authenticateAs(outsider);
        assertThat(status(get("/api/dbops/aws/{id}/agent-wait-events", GCP_ID))).isEqualTo(403);
    }

    // ── SEC-2/3: Explain disabled → 409 for an entitled caller ────────────────

    @Test
    void explainQueryDisabled_entitledCallerGets409() throws Exception {
        authenticateAs(owner);
        int status = status(post("/api/dbops/aws/{id}/explain-query", AWS_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(status).isEqualTo(409);
    }

    @Test
    void explainResultDisabled_entitledCallerGets409() throws Exception {
        authenticateAs(owner);
        assertThat(status(get("/api/dbops/aws/{id}/explain-result/{rid}", AWS_ID, "req-1")))
                .isEqualTo(409);
    }
}
