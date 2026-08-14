package com.xammer.mediaops.security;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.mediaops.domain.AppUser;
import com.xammer.mediaops.repository.AppUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * Validates impersonation JWTs issued by the billops service for "Login As".
 *
 * Mirrors {@code com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter} in
 * xamops-service. Without it, a BillOps admin who "Login As"-es into a customer
 * account gets 401 on every /api/media-ops/** call: the frontend sends those
 * requests with {@code credentials: 'omit'} and an {@code Authorization: Bearer}
 * header instead of JSESSIONID (see main.tsx), so there is no Redis session for
 * this service to restore and session-only auth has nothing to authenticate with.
 *
 * Runs in a STATELESS filter chain, so the admin's own session cookie is never
 * read, written, or replaced by an impersonated request.
 */
@Component
public class ImpersonationJwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationJwtFilter.class);

    @Value("${app.impersonation.secret-key}")
    private String secretKey;

    private final AppUserRepository appUserRepository;

    public ImpersonationJwtFilter(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
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
        try {
            SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey));
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .requireIssuer("billops")
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            Long userId = Long.parseLong(claims.getSubject());
            String role = claims.get("role", String.class);
            if (role == null || role.isBlank()) {
                role = "ROLE_XAMOPS"; // same fallback billops uses when the user has no role
            } else if (!role.startsWith("ROLE_")) {
                role = "ROLE_" + role;
            }

            // Resolve clientId from the shared DB: every media-ops controller scopes its
            // queries by ClientUserDetails.getClientId(), so an impersonated session with
            // a null clientId would see an empty MediaOps (or 401 from resolveClientId).
            AppUser user = appUserRepository.findById(userId).orElse(null);
            if (user == null) {
                log.warn("[MediaOps] Impersonation token references unknown user id {}", userId);
                unauthorized(response, "Impersonated user no longer exists");
                return;
            }

            ClientUserDetails userDetails = new ClientUserDetails(
                    user.getUsername(),
                    null,
                    Collections.singletonList(new SimpleGrantedAuthority(role)),
                    user.getClientId(),
                    userId,
                    claims.get("tenantId", String.class)
            );

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());

            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);

        } catch (JwtException | IllegalArgumentException e) {
            unauthorized(response, "Invalid or expired impersonation token");
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // STATELESS chain — nothing persists the context, but clear it so the
            // impersonated principal cannot leak onto a recycled request thread.
            SecurityContextHolder.clearContext();
        }
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
