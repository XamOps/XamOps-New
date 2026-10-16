package com.xammer.cloud.config.multitenancy;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * Validates impersonation JWTs issued by the billops service for "Login As".
 *
 * When present, the JWT authenticates the request as the target user without
 * touching the shared JSESSIONID cookie — so the admin's server session is
 * never read, written, or replaced.
 *
 * Registered as a Spring Security filter with STATELESS session policy, so the
 * HttpSession is never loaded or saved for these requests.
 *
 * The context is also saved to a request attribute, which is where that chain reads
 * it back. Spring Security 7 re-authorizes the ASYNC and ERROR dispatches of a request,
 * and this filter does not run on them, so without the save every CompletableFuture
 * endpoint, and every error response, came back to an impersonated tab as a 401, which
 * the frontend treats as a logout.
 */
@Component
public class ImpersonationJwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationJwtFilter.class);

    /**
     * Request attribute carrying the id of the admin behind an impersonated session.
     * Absent on ordinary requests; present on every request authenticated by this filter.
     */
    public static final String ATTR_ACTOR_USER_ID = "impersonation.actorUserId";

    /**
     * Stand-in for an impersonating admin whose identity the token does not record.
     * Negative so it can never collide with a real {@code app_user.id}.
     */
    public static final Long ACTOR_UNKNOWN = -1L;

    @Value("${app.impersonation.secret-key}")
    private String secretKey;

    /**
     * Signing key, resolved once instead of per request.
     *
     * Null when the configured value is unusable, in which case every impersonation
     * token is rejected. That is the same outcome as before -- decoding threw per
     * request and the catch below returned 401 -- but the reason is now stated once at
     * startup instead of being invisible.
     *
     * This key MUST be byte-identical to billops'; the token is minted there and
     * verified here. The two profiles historically held different values, so
     * impersonation would have failed with nothing in the logs explaining why. Both
     * services now read it from the single APP_IMPERSONATION_SECRET_KEY secret, so they
     * agree by construction -- and if it is ever absent, this says so.
     */
    private volatile SecretKey signingKey;

    @Autowired
    private UserRepository userRepository;

    /** Request-scoped only: never touches the HttpSession, so no JSESSIONID is issued. */
    private final SecurityContextRepository securityContextRepository =
            new RequestAttributeSecurityContextRepository();

    @PostConstruct
    void initSigningKey() {
        if (secretKey == null || secretKey.isBlank()) {
            log.error("app.impersonation.secret-key is not set (supplied via "
                    + "APP_IMPERSONATION_SECRET_KEY). Impersonation will be rejected. "
                    + "It must be Base64, decode to at least 32 bytes, and match billops "
                    + "exactly. Generate with: openssl rand -base64 32");
            return;
        }
        try {
            byte[] decoded = Decoders.BASE64.decode(secretKey.trim());
            if (decoded.length < 32) {
                log.error("app.impersonation.secret-key decodes to {} byte(s); HS256 needs at "
                        + "least 32. Impersonation will be rejected.", decoded.length);
                return;
            }
            this.signingKey = Keys.hmacShaKeyFor(decoded);
            log.info("Impersonation JWT filter initialised (HS256).");
        } catch (Exception e) {
            // Message only -- never the key.
            log.error("app.impersonation.secret-key is not valid Base64 (supplied via "
                    + "APP_IMPERSONATION_SECRET_KEY). Impersonation will be rejected: {}",
                    e.getMessage());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/llm-proxy/")
                || path.equals("/api/llm-proxy")
                || path.startsWith("/api/v1/agent/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        // Misconfigured key: reject rather than fall through unauthenticated. Same
        // response the per-request decode produced, minus the repeated work.
        SecretKey key = this.signingKey;
        if (key == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"message\":\"Impersonation is not configured on this service\"}");
            return;
        }

        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .requireIssuer("billops")
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            Long userId = Long.parseLong(claims.getSubject());
            String role = claims.get("role", String.class);
            if (role != null && !role.startsWith("ROLE_")) {
                role = "ROLE_" + role;
            }

            // Resolve clientId from DB so DashboardDataService.fetchUserAccounts()
            // can call cloudAccountRepository.findByClientId() and return real data.
            Long clientId = null;
            User user = userRepository.findById(userId).orElse(null);
            if (user != null && user.getClient() != null) {
                clientId = user.getClient().getId();
            }

            // Use the target user's real username (not the numeric JWT subject) so
            // principal.getName() matches what userRepository.findByUsername() expects —
            // mirrors ImpersonationFilter's session-based "Login As" path. Otherwise
            // controllers like SonarQubeController throw UsernameNotFoundException,
            // which surfaces as a 401 and the frontend treats it as a logout.
            String principalUsername = (user != null) ? user.getUsername() : claims.getSubject();

            ClientUserDetails userDetails = new ClientUserDetails(
                    principalUsername,
                    null,
                    Collections.singletonList(new SimpleGrantedAuthority(role)),
                    clientId,
                    userId,
                    TenantContext.getCurrentTenant()
            );

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());

            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            // So the ASYNC/ERROR dispatches of this request are authenticated too.
            securityContextRepository.saveContext(ctx, request, response);

            // Set ImpersonationContext so AccountManagerController filters
            // accounts to only those permitted for this user
            ImpersonationContext.setImpersonatedUserId(userId);

            // Store allowedAccountIds in request attribute for downstream use
            String allowedAccountIds = claims.get("allowedAccountIds", String.class);
            if (allowedAccountIds != null && !allowedAccountIds.isEmpty()) {
                request.setAttribute("impersonation.allowedAccountIds", allowedAccountIds);
            }

            // Which admin is driving, so activity recorded during this request can be
            // attributed to them rather than counted as the customer's own usage.
            //
            // Falls back to ACTOR_UNKNOWN rather than leaving the attribute unset. Tokens
            // minted before the claim existed are still valid for up to eight hours, and the
            // safe reading of one is "an admin, identity unrecorded" — not "the user did this
            // themselves". Any non-null value excludes the row from the subject's own totals,
            // so the fallback fails in the direction that cannot overstate engagement.
            Long actorUserId = ACTOR_UNKNOWN;
            try {
                Object claimed = claims.get("actorUserId");
                if (claimed instanceof Number n) {
                    actorUserId = n.longValue();
                }
            } catch (Exception ignored) {
                // Keep the fallback.
            }
            request.setAttribute(ATTR_ACTOR_USER_ID, actorUserId);

        } catch (JwtException | IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Invalid or expired impersonation token\"}");
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            ImpersonationContext.clear();
        }
    }
}
