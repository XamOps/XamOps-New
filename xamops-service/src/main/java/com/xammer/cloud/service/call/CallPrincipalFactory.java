package com.xammer.cloud.service.call;

import com.xammer.cloud.security.ClientUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Bridges an authenticated HTTP request to the identity the call layer uses.
 *
 * REST and STOMP arrive at the call code through different doors — a session on
 * one side, a signed token on the other — and both need to end up as the same
 * {@link CallUserPrincipal} so downstream logic has one notion of "who".
 */
@Component
public class CallPrincipalFactory {

    /**
     * @return null when the request is unauthenticated or carries a principal type
     *         the call layer cannot identify, which callers should treat as 401
     */
    public CallUserPrincipal fromAuthentication(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (!(authentication.getPrincipal() instanceof ClientUserDetails)) {
            return null;
        }
        ClientUserDetails details = (ClientUserDetails) authentication.getPrincipal();
        if (details.getId() == null) {
            return null;
        }

        Set<String> authorities = new LinkedHashSet<>();
        for (GrantedAuthority ga : details.getAuthorities()) {
            authorities.add(ga.getAuthority());
        }

        return new CallUserPrincipal(details.getId(), details.getUsername(),
                details.getUsername(), authorities, details.getClientId());
    }
}
