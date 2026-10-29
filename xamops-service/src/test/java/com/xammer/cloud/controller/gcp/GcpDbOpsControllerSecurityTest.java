package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.dbops.DbInstanceDto;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.gcp.GcpDbOpsService;
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
 * SEC-1 cross-tenant + provider-pin proof for {@link GcpDbOpsController}. The real
 * {@link CloudAccountAccessService} runs; assertions are on HTTP status only.
 */
class GcpDbOpsControllerSecurityTest {

    private static final String GCP_ID = "my-gcp-project";
    private static final String AWS_ID = "111111111111";

    private GcpDbOpsService gcpService;
    private MockMvc mvc;

    private final ClientUserDetails owner = new ClientUserDetails("owner@example.com", "pw",
            List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 10L, 500L, "tenant-a");
    private final ClientUserDetails outsider = new ClientUserDetails("outsider@example.com", "pw",
            List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 99L, 600L, "tenant-b");

    @BeforeEach
    void setUp() {
        gcpService = mock(GcpDbOpsService.class);
        CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        UserCloudAccountAccessRepository userAccessRepository = mock(UserCloudAccountAccessRepository.class);

        Client client10 = new Client();
        client10.setId(10L);

        CloudAccount gcpAccount = new CloudAccount();
        gcpAccount.setId(2L);
        gcpAccount.setProvider("GCP");
        gcpAccount.setGcpProjectId(GCP_ID);
        gcpAccount.setClient(client10);

        CloudAccount awsAccount = new CloudAccount();
        awsAccount.setId(1L);
        awsAccount.setProvider("AWS");
        awsAccount.setAwsAccountId(AWS_ID);
        awsAccount.setClient(client10);

        when(cloudAccountRepository.findByProviderAccountId(GCP_ID)).thenReturn(Optional.of(gcpAccount));
        when(cloudAccountRepository.findByProviderAccountId(AWS_ID)).thenReturn(Optional.of(awsAccount));
        when(userRepository.findById(500L)).thenReturn(Optional.of(new User()));
        when(userRepository.findById(600L)).thenReturn(Optional.of(new User()));
        when(userAccessRepository.findCloudAccountIdsByUserId(anyLong())).thenReturn(List.of());

        CloudAccountAccessService accessService = new CloudAccountAccessService(
                userRepository, userAccessRepository, cloudAccountRepository);

        GcpDbOpsController controller = new GcpDbOpsController(gcpService, accessService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        when(gcpService.getDbInventory(eq(GCP_ID), anyBoolean()))
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

    private int status(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        return result.getResponse().getStatus();
    }

    @Test
    void ownerGets200() throws Exception {
        authenticateAs(owner);
        assertThat(status(get("/api/dbops/gcp/{id}/inventory", GCP_ID))).isEqualTo(200);
    }

    @Test
    void outsiderGets403() throws Exception {
        authenticateAs(outsider);
        assertThat(status(get("/api/dbops/gcp/{id}/inventory", GCP_ID))).isEqualTo(403);
    }

    @Test
    void awsIdentifierGets403_providerAssertion() throws Exception {
        // The owner is entitled to the AWS account, but the GCP controller pins the provider.
        authenticateAs(owner);
        assertThat(status(get("/api/dbops/gcp/{id}/inventory", AWS_ID))).isEqualTo(403);
    }
}
