package com.xammer.billops.controller;

import com.xammer.billops.repository.UserActivityEventRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.security.ClientUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A "Login As" token names the admin who asked for it: id, username and role. xamops-service records that
 * admin, not the customer, as the actor of the XamLens reads made in the session.
 */
class ImpersonationTokenControllerTest {

    /** Base64 of "test-only-impersonation-signing-key-not-a-secret" (48 bytes; HS256 needs 32+). */
    private static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";
    private static final long ADMIN_ID = 7L;
    private static final String ADMIN_USERNAME = "billops-admin@example.com";
    private static final long TARGET_ID = 500L;

    private ImpersonationTokenController controller;

    @BeforeEach
    void setUp() {
        User target = new User();
        target.setId(TARGET_ID);
        target.setRole("ROLE_XAMOPS");
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

        controller = new ImpersonationTokenController();
        ReflectionTestUtils.setField(controller, "secretKey", SECRET);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "userActivityEventRepository", mock(UserActivityEventRepository.class));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_BILLOPS_ADMIN", "ROLE_BILLOPS_ADMIN_SUB"})
    void tokenNamesTheAdmin(String adminRole) {
        ClientUserDetails admin = new ClientUserDetails(
                ADMIN_USERNAME, "pw", List.of(new SimpleGrantedAuthority(adminRole)), 1L, ADMIN_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        ImpersonationTokenController.ImpersonationTokenRequest request =
                new ImpersonationTokenController.ImpersonationTokenRequest();
        request.setUserId(TARGET_ID);
        request.setTenantId("tenant-a");

        String token = controller.generateToken(request, new MockHttpServletRequest()).getBody().get("token");
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .requireIssuer("billops")
                .build()
                .parseClaimsJws(token)
                .getBody();

        assertThat(claims.get("actorUserId", Long.class)).isEqualTo(ADMIN_ID);
        assertThat(claims.get("actorUsername", String.class)).isEqualTo(ADMIN_USERNAME);
        assertThat(claims.get("actorRole", String.class)).isEqualTo(adminRole);
        // The identity being worn is still the target's.
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(TARGET_ID));
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_XAMOPS");
    }
}
