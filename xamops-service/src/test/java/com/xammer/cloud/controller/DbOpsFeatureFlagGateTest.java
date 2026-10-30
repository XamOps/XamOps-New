package com.xammer.cloud.controller;

import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.dbops.DbSavingsSummaryDto;
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

/**
 * Phase 1a fail-closed proof for {@link DbOpsController}: with every DbOps sub-feature flag at its
 * default {@code false}, an entitled caller still gets a 200, but the response carries NO data.
 *
 * <p>
 * The setup mirrors {@link DbOpsControllerSecurityTest}: the REAL {@link CloudAccountAccessService}
 * runs (its repositories mocked) so the SEC-1 entitlement decision is the production one and the
 * owner passes it; the flag check that follows is what empties the output. The list endpoints are
 * gated in the controller, so {@link DbOpsCostService} is deliberately left un-stubbed for them —
 * if a gate regressed and the controller fell through to the service, the un-stubbed mock would
 * not yield a 200 empty array and the test would fail. The summary is aggregated inside the
 * (mocked) service, so its gated flags-off shape (idle/right-sizing pillars at 0) is asserted as
 * the contract the endpoint serializes for an entitled caller.
 */
class DbOpsFeatureFlagGateTest {

    private static final String AWS_ID = "111111111111";

    private DbOpsCostService dbOpsCostService;
    private CloudAccountRepository cloudAccountRepository;
    private UserRepository userRepository;
    private UserCloudAccountAccessRepository userAccessRepository;
    private MockMvc mvc;

    /** Root user of client 10, which owns the AWS account below (entitled caller). */
    private final ClientUserDetails owner = new ClientUserDetails("owner@example.com", "pw",
            List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 10L, 500L, "tenant-a");

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

        when(cloudAccountRepository.findByAwsAccountId(AWS_ID)).thenReturn(List.of(awsAccount));
        when(cloudAccountRepository.findByProviderAccountId(AWS_ID)).thenReturn(Optional.of(awsAccount));

        // Root user with no explicit grants → client-wide entitlement (production root-user path).
        when(userRepository.findById(500L)).thenReturn(Optional.of(new User()));
        when(userAccessRepository.findCloudAccountIdsByUserId(anyLong())).thenReturn(List.of());

        // Flags-off summary shape: idle/right-sizing pillars contribute 0 (default int fields).
        when(dbOpsCostService.getSavingsSummary(eq(AWS_ID), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(new DbSavingsSummaryDto()));

        CloudAccountAccessService accessService = new CloudAccountAccessService(
                userRepository, userAccessRepository, cloudAccountRepository);

        DbOpsController controller = new DbOpsController(dbOpsCostService, cloudAccountRepository,
                mock(DbAgentConfigRepository.class), mock(DbAgentDataCacheRepository.class),
                mock(ExplainRequestRepository.class), mock(DbHealthSnapshotRepository.class),
                mock(DbHealthAlertRepository.class), accessService,
                new DbOpsFeatureFlags(false)); // all five Phase-1a flags default to false

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Performs the request, dispatching async handlers, and returns the final response. */
    private MvcResult dispatch(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        return result;
    }

    private void assertEmptyArray(String pathTemplate) throws Exception {
        MvcResult result = dispatch(get(pathTemplate, AWS_ID));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString().trim()).isEqualTo("[]");
    }

    @Test
    void idleDisabled_returnsEmptyArray() throws Exception {
        assertEmptyArray("/api/dbops/aws/{id}/idle");
    }

    @Test
    void rightsizingDisabled_returnsEmptyArray() throws Exception {
        assertEmptyArray("/api/dbops/aws/{id}/rightsizing");
    }

    @Test
    void healthSnapshotsDisabled_returnsEmptyArray() throws Exception {
        assertEmptyArray("/api/dbops/aws/{id}/health-snapshots");
    }

    @Test
    void dbAlertsDisabled_returnsEmptyArray() throws Exception {
        assertEmptyArray("/api/dbops/aws/{id}/db-alerts");
    }

    @Test
    void mongoMetricsDisabled_returnsEmptyArray() throws Exception {
        assertEmptyArray("/api/dbops/aws/{id}/agent-mongo-metrics");
    }

    @Test
    void redisMetricsDisabled_returnsEmptyArray() throws Exception {
        assertEmptyArray("/api/dbops/aws/{id}/agent-redis-metrics");
    }

    @Test
    void summaryDisabled_reportsZeroIdleAndRightsizing() throws Exception {
        MvcResult result = dispatch(get("/api/dbops/aws/{id}/summary", AWS_ID));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"idleInstances\":0");
        assertThat(body).contains("\"rightsizingCandidates\":0");
    }
}
