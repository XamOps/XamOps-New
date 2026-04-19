package com.xammer.cloud.util;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;

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
        return isSuperAdmin(getPrincipal());
    }

    public static boolean isSuperAdmin(ClientUserDetails userDetails) {
        if (userDetails == null) return false;
        return userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }

    public static boolean isAuthorizedForAccount(CloudAccount account, ClientUserDetails userDetails) {
        if (account == null || userDetails == null) {
            return false;
        }
        // SuperAdmins can access everything
        if (isSuperAdmin(userDetails)) {
            return true;
        }
        // Otherwise, client IDs must match
        return account.getClient() != null && Objects.equals(account.getClient().getId(), userDetails.getClientId());
    }
}