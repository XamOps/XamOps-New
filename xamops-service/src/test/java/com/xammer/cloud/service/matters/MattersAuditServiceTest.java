package com.xammer.cloud.service.matters;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.config.multitenancy.ImpersonationFilter;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.MattersAccessAudit;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who {@link MattersAuditService} records as the actor of a XamLens read. An admin's "view as user" goes
 * through the real {@link ImpersonationFilter} and BillOps Login-As through the real
 * {@link ImpersonationJwtFilter}, so the tests also cover how the service learns about the operator.
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
    private UserRepository userRepository;
    private byte[] jwtKey;

    @BeforeEach
    void setUp() {
        writer = mock(MattersAuditWriter.class);
        auditService = new MattersAuditService(writer);

        // The viewed user's users row, read by both filters: a ROLE_XAMOPS user of another client.
        Client client = new Client();
        client.setId(VIEWED_CLIENT_ID);
        User viewed = new User();
        viewed.setId(VIEWED_ID);
        viewed.setUsername(VIEWED_USERNAME);
        viewed.setRole("ROLE_XAMOPS");
        viewed.setClient(client);
        userRepository = mock(UserRepository.class);
        when(userRepository.findById(VIEWED_ID)).thenReturn(Optional.of(viewed));

        jwtKey = new byte[32];
        new SecureRandom().nextBytes(jwtKey);
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

    /** A XamLens read on the request thread, for the client the controllers read for. */
    private void read() {
        auditService.recordCredentialUse(currentClientId(), KEY_ID, PROJECT, ENDPOINT, 12);
    }

    private ImpersonationFilter headerFilter() {
        ImpersonationFilter filter = new ImpersonationFilter();
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);
        return filter;
    }

    private ImpersonationJwtFilter jwtFilter() {
        ImpersonationJwtFilter filter = new ImpersonationJwtFilter();
        ReflectionTestUtils.setField(filter, "secretKey", Base64.getEncoder().encodeToString(jwtKey));
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);
        ReflectionTestUtils.invokeMethod(filter, "initSigningKey");
        return filter;
    }

    /**
     * Runs {@code read} inside the real ImpersonationFilter on an admin's "view as user" request: the session
     * holds the admin's context, which Spring Security has loaded onto the thread, and the request carries
     * {@code X-Impersonate-User} for the viewed user.
     */
    private void underViewAs(String adminRole, Runnable read) throws Exception {
        SecurityContext adminContext = contextOf(principal(ADMIN_ID, ADMIN_CLIENT_ID, ADMIN_USERNAME, adminRole));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", adminContext);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dspm/datastores");
        request.setSession(session);
        request.addHeader(ImpersonationFilter.IMPERSONATION_HEADER, String.valueOf(VIEWED_ID));
        SecurityContextHolder.setContext(adminContext);
        headerFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(currentClientId()).as("the request reads the viewed user's client").isEqualTo(VIEWED_CLIENT_ID);
            read.run();
        });
    }

    /**
     * A BillOps Login-As request: a bearer token for the viewed user, minted as billops'
     * ImpersonationTokenController does, with whichever of the actor claims {@code actorClaims} holds.
     */
    private MockHttpServletRequest loginAsRequest(Map<String, Object> actorClaims) {
        String token = Jwts.builder()
                .setSubject(String.valueOf(VIEWED_ID))
                .claim("role", "ROLE_XAMOPS")
                .addClaims(actorClaims)
                .setIssuer("billops")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(jwtKey), SignatureAlgorithm.HS256)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dspm/datastores");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    /** The actor claims billops mints now: the admin's id, username and role. */
    private static Map<String, Object> namedAdmin(String role) {
        return Map.of("actorUserId", ADMIN_ID, "actorUsername", ADMIN_USERNAME, "actorRole", role);
    }

    /** Makes a XamLens read inside the real ImpersonationJwtFilter, and checks the token was accepted. */
    private void readUnderLoginAs(Map<String, Object> actorClaims) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        jwtFilter().doFilter(loginAsRequest(actorClaims), response, (req, res) -> read());
        assertThat(response.getStatus()).as("the token is accepted").isEqualTo(200);
    }

    /** The row the service handed to the writer. */
    private MattersAccessAudit written() {
        ArgumentCaptor<MattersAccessAudit> row = ArgumentCaptor.forClass(MattersAccessAudit.class);
        verify(writer).writeAsync(row.capture());
        return row.getValue();
    }

    /** An impersonated OPERATOR row with this actor, still attributed to the viewed user's client. */
    private static void assertOperatorRow(MattersAccessAudit row, Long actorUserId, String actorUsername, String actorRole) {
        assertThat(row.getActorType()).isEqualTo(MattersAuditService.ACTOR_OPERATOR);
        assertThat(row.isImpersonated()).isTrue();
        assertThat(row.getActorUserId()).isEqualTo(actorUserId);
        assertThat(row.getActorUsername()).isEqualTo(actorUsername);
        assertThat(row.getActorRole()).isEqualTo(actorRole);
        assertThat(row.getClientId()).as("still attributed to the viewed user's client").isEqualTo(VIEWED_CLIENT_ID);
    }

    @Test
    @DisplayName("a super admin's view-as read is an OPERATOR row with the super admin as actor, impersonated, on the viewed client")
    void superAdminViewAsRead() throws Exception {
        underViewAs("ROLE_SUPER_ADMIN", this::read);
        assertOperatorRow(written(), ADMIN_ID, ADMIN_USERNAME, "ROLE_SUPER_ADMIN");
    }

    @Test
    @DisplayName("a view-as read on a thread that carries the copied security context, as the profile pool does, is attributed the same")
    void superAdminViewAsReadOnACopiedContext() throws Exception {
        underViewAs("ROLE_SUPER_ADMIN", () -> {
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
        assertOperatorRow(row, ADMIN_ID, ADMIN_USERNAME, "ROLE_SUPER_ADMIN");
        assertThat(row.getOutcome()).isEqualTo(MattersAccessAudit.OUTCOME_FAILURE);
    }

    @Test
    @DisplayName("a BillOps admin's view-as read is an OPERATOR row with the BillOps admin as actor, impersonated, on the viewed client")
    void billopsAdminViewAsRead() throws Exception {
        underViewAs("ROLE_BILLOPS_ADMIN", this::read);
        assertOperatorRow(written(), ADMIN_ID, ADMIN_USERNAME, "ROLE_BILLOPS_ADMIN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_BILLOPS_ADMIN", "ROLE_BILLOPS_ADMIN_SUB"})
    @DisplayName("a Login-As read with a token that names the admin is an OPERATOR row with that admin as actor, impersonated, on the viewed client")
    void loginAsReadWithTheAdminNamed(String adminRole) throws Exception {
        readUnderLoginAs(namedAdmin(adminRole));
        assertOperatorRow(written(), ADMIN_ID, ADMIN_USERNAME, adminRole);
    }

    @Test
    @DisplayName("a Login-As token with only actorUserId still works, and its reads record the admin's id with no username or role")
    void loginAsReadWithOnlyTheAdminsId() throws Exception {
        readUnderLoginAs(Map.of("actorUserId", ADMIN_ID));
        assertOperatorRow(written(), ADMIN_ID, null, null);
    }

    @Test
    @DisplayName("a Login-As token with no actor claims still works, and its reads record an unknown operator, never the customer")
    void loginAsReadWithNoActorClaims() throws Exception {
        readUnderLoginAs(Map.of());
        assertOperatorRow(written(), ImpersonationJwtFilter.ACTOR_UNKNOWN, null, null);
    }

    @Test
    @DisplayName("a Login-As request that also carries X-Impersonate-User and a super admin's session cookie records the token's admin")
    void loginAsThroughTheHeaderFilterKeepsTheTokensAdmin() throws Exception {
        MockHttpServletRequest request = loginAsRequest(namedAdmin("ROLE_BILLOPS_ADMIN"));
        request.addHeader(ImpersonationFilter.IMPERSONATION_HEADER, String.valueOf(VIEWED_ID));
        MockHttpSession cookie = new MockHttpSession();
        cookie.setAttribute("SPRING_SECURITY_CONTEXT",
                contextOf(principal(2L, ADMIN_CLIENT_ID, "super@example.com", "ROLE_SUPER_ADMIN")));
        request.setSession(cookie);
        ImpersonationFilter headerFilter = headerFilter();

        // The app's order: the JWT filter runs inside Spring Security, the header filter after it.
        jwtFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            Authentication fromToken = SecurityContextHolder.getContext().getAuthentication();
            headerFilter.doFilter(req, res, (innerReq, innerRes) -> {
                assertThat(SecurityContextHolder.getContext().getAuthentication())
                        .as("the header filter installed its own authentication").isNotSameAs(fromToken);
                read();
            });
        });
        assertOperatorRow(written(), ADMIN_ID, ADMIN_USERNAME, "ROLE_BILLOPS_ADMIN");
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
