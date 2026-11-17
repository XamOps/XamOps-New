package com.xammer.cloud.config.multitenancy;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.stream.Collectors;

@Component
@Order(2) // Run AFTER TenantFilter
public class ImpersonationFilter implements Filter {

    public static final String IMPERSONATION_HEADER = "X-Impersonate-User";

    private static final String SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    @Autowired
    private UserRepository userRepository;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        String impersonateId = req.getHeader(IMPERSONATION_HEADER);

        // Capture the original (admin) SecurityContext BEFORE any impersonation.
        // We restore it in the finally block so Spring Security's
        // SecurityContextPersistenceFilter does NOT write the impersonated auth back
        // to the HTTP session — which would contaminate every other browser tab that
        // shares the same JSESSIONID cookie (e.g. the admin's original Tab A).
        SecurityContext originalContext = SecurityContextHolder.getContext();

        if (impersonateId != null && !impersonateId.isEmpty()) {
            // SECURITY CHECK: Only allow impersonation if the current user is an admin
            // or if we have an existing verified admin session marker
            if (isAllowedToImpersonate(req)) {
                try {
                    Long targetUserId = Long.parseLong(impersonateId);
                    ImpersonationContext.setImpersonatedUserId(targetUserId);

                    userRepository.findById(targetUserId).ifPresent(user -> {
                        String role = user.getRole();
                        if (role != null && !role.startsWith("ROLE_")) {
                            role = "ROLE_" + role;
                        }

                        ClientUserDetails targetDetails = new ClientUserDetails(
                                user.getUsername(),
                                null,
                                Collections.singletonList(new SimpleGrantedAuthority(role)),
                                user.getClient() != null ? user.getClient().getId() : null,
                                user.getId(),
                                TenantContext.getCurrentTenant());

                        UsernamePasswordAuthenticationToken impersonatedAuth = new UsernamePasswordAuthenticationToken(
                                targetDetails, null, targetDetails.getAuthorities());

                        // Decided on the identity that authenticated this request, not on the session
                        // marker, so a BillOps admin or a Login-As JWT (billops never mints one for
                        // ROLE_SUPER_ADMIN) is never taken for a super admin.
                        Authentication admin = originalContext.getAuthentication();
                        boolean superAdmin = isSuperAdmin(admin);
                        if (superAdmin) {
                            // Who is driving, for MattersAuditService. On the Authentication rather than
                            // in a ThreadLocal, so a copied security context (MattersDspmService's
                            // profile pool) carries it too.
                            impersonatedAuth.setDetails(ImpersonatingSuperAdmin.of(admin));
                        }

                        // Use a FRESH SecurityContext so the original session-backed context
                        // object is never mutated. The original is restored in the finally block.
                        SecurityContext impersonatedContext = SecurityContextHolder.createEmptyContext();
                        impersonatedContext.setAuthentication(impersonatedAuth);
                        SecurityContextHolder.setContext(impersonatedContext);

                        // XamLensTabGuard lets a super admin's "view as user" through whatever the
                        // target's role or tab rules. Cleared below.
                        ImpersonationContext.setStartedBySuperAdmin(superAdmin);
                    });
                } catch (NumberFormatException e) {
                    // Ignore invalid headers
                }
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            ImpersonationContext.clear();
            // Restore the original admin SecurityContext so Spring Security's
            // SecurityContextPersistenceFilter saves the admin's auth back to the
            // HTTP session — not the impersonated user's auth.
            SecurityContextHolder.setContext(originalContext);
        }
    }

    /** Whether {@code auth}, the request's own authentication before the swap, holds ROLE_SUPER_ADMIN. */
    private static boolean isSuperAdmin(Authentication auth) {
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> SUPER_ADMIN.equals(a.getAuthority()));
    }

    /**
     * The super admin behind a header impersonation ("view as user"), set as the impersonated
     * Authentication's details. Only a request authenticated as ROLE_SUPER_ADMIN gets one;
     * {@code MattersAuditService} records it as the actor of the XamLens reads made on the request.
     *
     * @param userId null when the admin's principal is not a {@link ClientUserDetails}
     */
    public record ImpersonatingSuperAdmin(Long userId, String username, String role) implements Serializable {

        static ImpersonatingSuperAdmin of(Authentication admin) {
            Long id = admin.getPrincipal() instanceof ClientUserDetails details ? details.getId() : null;
            return new ImpersonatingSuperAdmin(id, admin.getName(), SUPER_ADMIN);
        }
    }

    /**
     * Helper to verify if the requester has ROLE_SUPER_ADMIN or ROLE_BILLOPS_ADMIN
     */
    private boolean isAllowedToImpersonate(HttpServletRequest req) {
        try {
            HttpSession session = req.getSession(false);
            if (session != null) {
                // If we already verified an admin in this session, allow sequential
                // impersonations
                if (session.getAttribute("X-ORIGINAL-ADMIN-ID") != null) {
                    return true;
                }

                Object securityContextObj = session.getAttribute("SPRING_SECURITY_CONTEXT");
                if (securityContextObj instanceof SecurityContext) {
                    SecurityContext securityContext = (SecurityContext) securityContextObj;
                    Authentication auth = securityContext.getAuthentication();

                    if (auth != null && auth.isAuthenticated()) {
                        boolean isAdmin = auth.getAuthorities().stream()
                                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()) ||
                                        "ROLE_BILLOPS_ADMIN".equals(a.getAuthority()));

                        if (isAdmin) {
                            // First time impersonating - store the admin ID to persist authority
                            if (auth.getPrincipal() instanceof ClientUserDetails) {
                                session.setAttribute("X-ORIGINAL-ADMIN-ID",
                                        ((ClientUserDetails) auth.getPrincipal()).getId());
                            }
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail authentication check
        }
        return false;
    }
}