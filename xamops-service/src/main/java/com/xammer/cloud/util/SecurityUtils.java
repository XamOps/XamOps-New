package com.xammer.cloud.util;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.security.ClientUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtils {

    public static Long getCurrentUserId() {
        // 1. Check if Impersonation is active (Only valid if real user is SuperAdmin)
        Long impersonatedId = ImpersonationContext.getImpersonatedUserId();
        if (impersonatedId != null && isSuperAdmin()) {
            return impersonatedId;
        }

        // 2. Fallback to the actual logged-in user
        return getPrincipal().getId();
    }

    public static ClientUserDetails getPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ClientUserDetails) {
            return (ClientUserDetails) authentication.getPrincipal();
        }
        throw new RuntimeException("No authenticated user found");
    }

    public static boolean isSuperAdmin() {
        return getPrincipal().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }

    /**
     * Check if the current user is authorized to access a specific cloud account.
     * Authorized if:
     * 1. User is a SuperAdmin
     * 2. The account belongs to the user's client
     */
    public static boolean isAuthorizedForAccount(com.xammer.cloud.domain.CloudAccount account, ClientUserDetails userDetails) {
        if (isSuperAdmin()) {
            return true;
        }
        if (account == null || account.getClient() == null) {
            return false;
        }
        return java.util.Objects.equals(account.getClient().getId(), userDetails.getClientId());
    }
}