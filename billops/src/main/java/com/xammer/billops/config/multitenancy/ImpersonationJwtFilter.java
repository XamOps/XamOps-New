package com.xammer.billops.config.multitenancy;

import com.xammer.cloud.security.ClientUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Component
public class ImpersonationJwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationJwtFilter.class);

    @Value("${app.impersonation.secret-key}")
    private String secretKey;

    /**
     * Signing key, resolved once instead of per request.
     *
     * Null when the configured value is unusable. The filter then rejects every
     * impersonation token, which is the same outcome as before -- decoding threw per
     * request and the catch below returned 401 -- but the reason is now stated once at
     * startup rather than being invisible.
     *
     * That mattered here: the key MUST be identical in xamops-service and billops, and
     * historically the two profiles held different values, so impersonation would have
     * failed with nothing in the logs to say why.
     */
    private volatile SecretKey signingKey;

    /**
     * Where the impersonated context is saved for the rest of the request.
     *
     * The STATELESS chain reads the context back from a request attribute. Spring Security 7
     * re-authorizes the ASYNC and ERROR dispatches of a request, and this filter does not run
     * on them, so without the save those dispatches were anonymous and returned 401, which the
     * frontend treats as a logout. Request-scoped only: the HttpSession is never touched.
     */
    private final SecurityContextRepository securityContextRepository =
            new RequestAttributeSecurityContextRepository();

    @PostConstruct
    void initSigningKey() {
        if (secretKey == null || secretKey.isBlank()) {
            log.error("app.impersonation.secret-key is not set (supplied via "
                    + "APP_IMPERSONATION_SECRET_KEY). Impersonation will be rejected. "
                    + "It must be Base64, decode to at least 32 bytes, and match "
                    + "xamops-service exactly. Generate with: openssl rand -base64 32");
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

            ClientUserDetails userDetails = new ClientUserDetails(
                    claims.getSubject(),
                    null,
                    Collections.singletonList(new SimpleGrantedAuthority(role)),
                    null,
                    userId
            );

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());

            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            securityContextRepository.saveContext(ctx, request, response);

            ImpersonationContext.setImpersonatedUserId(userId);

            String allowedAccountIds = claims.get("allowedAccountIds", String.class);
            if (allowedAccountIds != null && !allowedAccountIds.isEmpty()) {
                request.setAttribute("impersonation.allowedAccountIds", allowedAccountIds);
            }

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
