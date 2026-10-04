package com.xammer.mediaops.security;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.mediaops.domain.AppUser;
import com.xammer.mediaops.repository.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A "Login As" request must leave its context where the STATELESS chain reads it back.
 *
 * <p>Spring Security 7 re-authorizes the ASYNC and ERROR dispatches of a request, and this filter
 * does not run on them. Those dispatches load the context from a
 * {@link RequestAttributeSecurityContextRepository}; this filter also clears the
 * SecurityContextHolder when its dispatch ends, so the request attribute is the only copy left.
 */
class ImpersonationJwtFilterTest {

    /** Base64 of "test-only-impersonation-signing-key-not-a-secret" (48 bytes; HS256 needs 32+). */
    private static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";

    private ImpersonationJwtFilter filter;

    @BeforeEach
    void setUp() {
        AppUser target = new AppUser();
        target.setId(42L);
        target.setUsername("target-user");
        target.setClientId(7L);

        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        when(appUserRepository.findById(42L)).thenReturn(Optional.of(target));

        filter = new ImpersonationJwtFilter(appUserRepository);
        ReflectionTestUtils.setField(filter, "secretKey", SECRET);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Minted the way billops' ImpersonationTokenController.buildToken does it. */
    private static String impersonationToken() {
        return Jwts.builder()
                .setSubject("42")
                .claim("role", "ROLE_XAMOPS")
                .setIssuer("billops")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60L * 60 * 1000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)), SignatureAlgorithm.HS256)
                .compact();
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/media-ops/jobs");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    void validTokenIsSavedForTheLaterDispatchesOfTheRequest() throws Exception {
        MockHttpServletRequest request = requestWithToken(impersonationToken());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Authentication saved = new RequestAttributeSecurityContextRepository()
                .loadDeferredContext(request).get().getAuthentication();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(saved).isNotNull();
        assertThat(saved.getName()).isEqualTo("target-user");
        assertThat(((ClientUserDetails) saved.getPrincipal()).getClientId()).isEqualTo(7L);
        assertThat(saved.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_XAMOPS");
        // The thread's copy is still cleared at the end of the dispatch.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // The impersonated tab must never be given (or overwrite) a JSESSIONID.
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void invalidTokenIsRejectedAndNothingIsSaved() throws Exception {
        MockHttpServletRequest request = requestWithToken("not-a-real-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"message\":\"Invalid or expired impersonation token\"}");
        assertThat(chain.getRequest()).isNull();
        assertThat(new RequestAttributeSecurityContextRepository().containsContext(request)).isFalse();
        assertThat(request.getSession(false)).isNull();
    }
}
