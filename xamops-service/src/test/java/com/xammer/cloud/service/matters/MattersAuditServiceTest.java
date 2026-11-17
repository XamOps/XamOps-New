package com.xammer.cloud.service.matters;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.config.multitenancy.ImpersonationFilter;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.MattersAccessAudit;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who {@link MattersAuditService} records as the actor of a XamLens read. A super admin's "view as user"
 * goes through the real {@link ImpersonationFilter}, so the test also covers how the service learns about
 * the super admin.
 */
class MattersAuditServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final long ADMIN_CLIENT_ID = 1L;
    private static final String ADMIN_USERNAME = "admin@example.com";
    private static final long VIEWED_ID = 500L;
    private static final long VIEWED_CLIENT_ID = 10L;
    private static final String VIEWED_USERNAME = "viewed-user";
    private static final String KEY_ID = "key-id";
    private static final String PROJECT = "project-1";
    private static final String ENDPOINT = "/api/v1/assets";

    private MattersAuditWriter writer;
    private MattersAuditService auditService;

    @BeforeEach
    void setUp() {
        writer = mock(MattersAuditWriter.class);
        auditService = new MattersAuditService(writer);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ImpersonationContext.clear();
    }

    private static ClientUserDetails principal(long id, long clientId, String username, String role) {
        return new ClientUserDetails(username, "pw", List.of(new SimpleGrantedAuthority(role)), clientId, id, "tenant-a");
    }

    private static SecurityContext contextOf(ClientUserDetails principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return context;
    }

    /** The client the controllers read for: the current principal's. */
    private static Long currentClientId() {
        return ((ClientUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getClientId();
    }

    /**
     * Runs {@code read} inside the real ImpersonationFilter on a super admin's "view as user" request: the
     * session holds the admin's context, which Spring Security has loaded onto the thread, and the request
     * carries {@code X-Impersonate-User} for a ROLE_XAMOPS user of another client.
     */
    private void underSuperAdminViewAs(Runnable read) throws Exception {
        Client client = new Client();
        client.setId(VIEWED_CLIENT_ID);
        User viewed = new User();
        viewed.setId(VIEWED_ID);
        viewed.setUsername(VIEWED_USERNAME);
        viewed.setRole("ROLE_XAMOPS");
        viewed.setClient(client);
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(VIEWED_ID)).thenReturn(Optional.of(viewed));
        ImpersonationFilter filter = new ImpersonationFilter();
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);

        SecurityContext adminContext = contextOf(principal(ADMIN_ID, ADMIN_CLIENT_ID, ADMIN_USERNAME, "ROLE_SUPER_ADMIN"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", adminContext);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dspm/datastores");
        request.setSession(session);
        request.addHeader(ImpersonationFilter.IMPERSONATION_HEADER, String.valueOf(VIEWED_ID));
        SecurityContextHolder.setContext(adminContext);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(currentClientId()).as("the request reads the viewed user's client").isEqualTo(VIEWED_CLIENT_ID);
            read.run();
        });
    }

    /** The row the service handed to the writer. */
    private MattersAccessAudit written() {
        ArgumentCaptor<MattersAccessAudit> row = ArgumentCaptor.forClass(MattersAccessAudit.class);
        verify(writer).writeAsync(row.capture());
        return row.getValue();
    }

    private static void assertSuperAdminViewAs(MattersAccessAudit row) {
        assertThat(row.getActorType()).isEqualTo(MattersAuditService.ACTOR_OPERATOR);
        assertThat(row.isImpersonated()).isTrue();
        assertThat(row.getActorUserId()).isEqualTo(ADMIN_ID);
        assertThat(row.getActorUsername()).isEqualTo(ADMIN_USERNAME);
        assertThat(row.getActorRole()).isEqualTo("ROLE_SUPER_ADMIN");
        assertThat(row.getClientId()).as("still attributed to the viewed user's client").isEqualTo(VIEWED_CLIENT_ID);
    }

    @Test
    @DisplayName("a super admin's view-as read is an OPERATOR row with the super admin as actor, impersonated, on the viewed client")
    void superAdminViewAsRead() throws Exception {
        underSuperAdminViewAs(() -> auditService.recordCredentialUse(currentClientId(), KEY_ID, PROJECT, ENDPOINT, 12));
        assertSuperAdminViewAs(written());
    }

    @Test
    @DisplayName("a view-as read on a thread that carries the copied security context, as the profile pool does, is attributed the same")
    void superAdminViewAsReadOnACopiedContext() throws Exception {
        underSuperAdminViewAs(() -> {
            SecurityContext copied = SecurityContextHolder.getContext();
            Long clientId = currentClientId();
            CompletableFuture.runAsync(() -> {
                SecurityContextHolder.setContext(copied);
                try {
                    auditService.recordCredentialFailure(clientId, KEY_ID, PROJECT, ENDPOINT,
                            MattersApiException.Reason.UPSTREAM_UNAVAILABLE.name(), "Matters did not answer");
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }).join();
        });
        MattersAccessAudit row = written();
        assertSuperAdminViewAs(row);
        assertThat(row.getOutcome()).isEqualTo(MattersAccessAudit.OUTCOME_FAILURE);
    }

    @Test
    @DisplayName("a customer's own read is unchanged: CUSTOMER, not impersonated, the customer as actor")
    void customerReadIsUnchanged() {
        SecurityContextHolder.setContext(contextOf(principal(VIEWED_ID, VIEWED_CLIENT_ID, VIEWED_USERNAME, "ROLE_XAMOPS")));
        auditService.recordCredentialUse(VIEWED_CLIENT_ID, KEY_ID, PROJECT, ENDPOINT, 12);
        MattersAccessAudit row = written();
        assertThat(row.getActorType()).isEqualTo(MattersAuditService.ACTOR_CUSTOMER);
        assertThat(row.isImpersonated()).isFalse();
        assertThat(row.getActorUserId()).isEqualTo(VIEWED_ID);
        assertThat(row.getActorUsername()).isEqualTo(VIEWED_USERNAME);
        assertThat(row.getActorRole()).isEqualTo("ROLE_XAMOPS");
        assertThat(row.getClientId()).isEqualTo(VIEWED_CLIENT_ID);
    }

    @Test
    @DisplayName("a direct super-admin read is unchanged: OPERATOR, not impersonated, the super admin as actor")
    void directSuperAdminReadIsUnchanged() {
        SecurityContextHolder.setContext(contextOf(principal(ADMIN_ID, ADMIN_CLIENT_ID, ADMIN_USERNAME, "ROLE_SUPER_ADMIN")));
        auditService.recordCredentialUse(ADMIN_CLIENT_ID, KEY_ID, PROJECT, ENDPOINT, 12);
        MattersAccessAudit row = written();
        assertThat(row.getActorType()).isEqualTo(MattersAuditService.ACTOR_OPERATOR);
        assertThat(row.isImpersonated()).isFalse();
        assertThat(row.getActorUserId()).isEqualTo(ADMIN_ID);
        assertThat(row.getActorUsername()).isEqualTo(ADMIN_USERNAME);
        assertThat(row.getActorRole()).isEqualTo("ROLE_SUPER_ADMIN");
        assertThat(row.getClientId()).isEqualTo(ADMIN_CLIENT_ID);
    }
}
