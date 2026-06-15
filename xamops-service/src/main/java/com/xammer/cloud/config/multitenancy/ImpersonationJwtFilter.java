package com.xammer.cloud.config.multitenancy;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
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
 * When present, the JWT authenticates the request as the target user without
 * touching the shared JSESSIONID cookie — so the admin's server session is
 * never read, written, or replaced.
 *
 * Registered as a Spring Security filter with STATELESS session policy, so
 * SecurityContextPersistenceFilter skips session load/save entirely for these
 * requests.
 */
@Component
public class ImpersonationJwtFilter extends OncePerRequestFilter {

    @Value("${app.impersonation.secret-key}")
    private String secretKey;

    @Autowired
    private UserRepository userRepository;

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

            // Set ImpersonationContext so AccountManagerController filters
            // accounts to only those permitted for this user
            ImpersonationContext.setImpersonatedUserId(userId);

            // Store allowedAccountIds in request attribute for downstream use
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
