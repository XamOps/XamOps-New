package com.xammer.billops.controller;

import com.xammer.billops.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.billops.repository.UserActivityEventRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.billops.util.SecurityUtils;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.security.ClientUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A "Login As" token names the admin who asked for it: id, username and role. xamops-service records that
 * admin, not the customer, as the actor of the XamLens reads made in the session.
 *
 * <p>That holds along a chain too: an admin who logs in as a BillOps admin account and clicks Login-As
 * there gets a token that still names the admin, not the account being worn.
 */
class ImpersonationTokenControllerTest {

    /** Base64 of "test-only-impersonation-signing-key-not-a-secret" (48 bytes; HS256 needs 32+). */
    private static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";
    private static final long ADMIN_ID = 7L;
    private static final String ADMIN_USERNAME = "billops-admin@example.com";
    /** A BillOps admin account the admin logs in as, and mints the next token from. */
    private static final long WORN_ID = 300L;
    private static final long TARGET_ID = 500L;

    private ImpersonationTokenController controller;
    private ImpersonationJwtFilter filter;

    @BeforeEach
    void setUp() {
        User worn = new User();
        worn.setId(WORN_ID);
        worn.setRole("ROLE_BILLOPS_ADMIN");
        User target = new User();
        target.setId(TARGET_ID);
        target.setRole("ROLE_XAMOPS");
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(WORN_ID)).thenReturn(Optional.of(worn));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

        controller = new ImpersonationTokenController();
        ReflectionTestUtils.setField(controller, "secretKey", SECRET);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "userActivityEventRepository", mock(UserActivityEventRepository.class));

        filter = new ImpersonationJwtFilter();
        ReflectionTestUtils.setField(filter, "secretKey", SECRET);
        ReflectionTestUtils.invokeMethod(filter, "initSigningKey");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_BILLOPS_ADMIN", "ROLE_BILLOPS_ADMIN_SUB"})
    void tokenNamesTheAdmin(String adminRole) {
        signInAsAdmin(adminRole);

        Claims claims = parse(mint(TARGET_ID, new MockHttpServletRequest()));

        assertThat(claims.get("actorUserId", Long.class)).isEqualTo(ADMIN_ID);
        assertThat(claims.get("actorUsername", String.class)).isEqualTo(ADMIN_USERNAME);
        assertThat(claims.get("actorRole", String.class)).isEqualTo(adminRole);
        // The identity being worn is still the target's.
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(TARGET_ID));
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_XAMOPS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_BILLOPS_ADMIN", "ROLE_BILLOPS_ADMIN_SUB"})
    void tokenMintedInsideALoginAsSessionNamesTheAdminWhoStartedIt(String adminRole) throws Exception {
        signInAsAdmin(adminRole);
        String wornToken = mint(WORN_ID, new MockHttpServletRequest());
        SecurityContextHolder.clearContext();

        Claims claims = parse(mintInsideLoginAs(wornToken, TARGET_ID));

        assertThat(claims.get("actorUserId", Long.class)).isEqualTo(ADMIN_ID);
        assertThat(claims.get("actorUsername", String.class)).isEqualTo(ADMIN_USERNAME);
        assertThat(claims.get("actorRole", String.class)).isEqualTo(adminRole);
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(TARGET_ID));
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_XAMOPS");
    }

    /** Minted before actorUsername and actorRole existed: the id is all it proves, so only the id is forwarded. */
    @Test
    void tokenMintedFromAnOlderTokenForwardsTheActorIdAlone() throws Exception {
        Claims claims = parse(mintInsideLoginAs(olderWornToken(ADMIN_ID), TARGET_ID));

        assertThat(claims.get("actorUserId", Long.class)).isEqualTo(ADMIN_ID);
        assertThat(claims).doesNotContainKeys("actorUsername", "actorRole");
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(TARGET_ID));
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_XAMOPS");
    }

    /** Names no actor rather than the worn account; xamops-service records it as an operator of unknown identity. */
    @Test
    void tokenMintedFromATokenWithoutActorClaimsNamesNoActor() throws Exception {
        Claims claims = parse(mintInsideLoginAs(olderWornToken(null), TARGET_ID));

        assertThat(claims).doesNotContainKeys("actorUserId", "actorUsername", "actorRole");
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(TARGET_ID));
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_XAMOPS");
    }

    /** The admin's own session. */
    private static void signInAsAdmin(String adminRole) {
        ClientUserDetails admin = new ClientUserDetails(
                ADMIN_USERNAME, "pw", List.of(new SimpleGrantedAuthority(adminRole)), 1L, ADMIN_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    private String mint(long targetId, HttpServletRequest httpRequest) {
        ImpersonationTokenController.ImpersonationTokenRequest request =
                new ImpersonationTokenController.ImpersonationTokenRequest();
        request.setUserId(targetId);
        request.setTenantId("tenant-a");
        return controller.generateToken(request, httpRequest).getBody().get("token");
    }

    /**
     * Mints from a request the way a Login-As tab sends it: with the worn account's token, which billops' own
     * {@link ImpersonationJwtFilter} verifies before the controller runs.
     */
    private String mintInsideLoginAs(String incomingToken, long targetId) throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/billops/generate-impersonation-token");
        request.addHeader("Authorization", "Bearer " + incomingToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> minted = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            // The principal minting the token is the worn account.
            assertThat(SecurityUtils.getPrincipal().getId()).isEqualTo(WORN_ID);
            minted.set(mint(targetId, (HttpServletRequest) req));
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(minted.get()).isNotNull();
        return minted.get();
    }

    /** A Login-As token into the worn account, as billops minted them before actorUsername and actorRole. */
    private static String olderWornToken(Long actorUserId) {
        var builder = Jwts.builder()
                .setSubject(String.valueOf(WORN_ID))
                .claim("role", "ROLE_BILLOPS_ADMIN")
                .setIssuer("billops")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60L * 60 * 1000));
        if (actorUserId != null) builder.claim("actorUserId", actorUserId);
        return builder.signWith(key(), SignatureAlgorithm.HS256).compact();
    }

    private static Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key())
                .requireIssuer("billops")
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private static SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    }
}
