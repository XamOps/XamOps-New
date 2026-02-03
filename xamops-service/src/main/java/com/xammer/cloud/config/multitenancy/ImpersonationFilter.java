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
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Collections;
import java.util.stream.Collectors;

@Component
@Order(2) // Run AFTER TenantFilter
public class ImpersonationFilter implements Filter {

    public static final String IMPERSONATION_HEADER = "X-Impersonate-User";

    @Autowired
    private UserRepository userRepository;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        String impersonateId = req.getHeader(IMPERSONATION_HEADER);

        if (impersonateId != null && !impersonateId.isEmpty()) {
            // SECURITY CHECK: Only allow impersonation if the current user is an admin
            // or if we have an existing verified admin session marker
            if (isAllowedToImpersonate(req)) {
                try {
                    Long targetUserId = Long.parseLong(impersonateId);
                    ImpersonationContext.setImpersonatedUserId(targetUserId);

                    // --- NEW: Override Security Context for the duration of the request ---
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
                                user.getId());

                        UsernamePasswordAuthenticationToken impersonatedAuth = new UsernamePasswordAuthenticationToken(
                                targetDetails, null, targetDetails.getAuthorities());

                        SecurityContextHolder.getContext().setAuthentication(impersonatedAuth);
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