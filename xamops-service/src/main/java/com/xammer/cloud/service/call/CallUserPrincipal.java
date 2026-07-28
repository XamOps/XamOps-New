package com.xammer.cloud.service.call;

import java.security.Principal;
import java.util.Collections;
import java.util.Set;

/**
 * Identity attached to a STOMP session for call signalling.
 *
 * The WebSocket handshake cannot be trusted to carry the HTTP session here: the
 * tickets UI authenticates against billops while the socket terminates on
 * xamops-service, and {@code /xamops-ws/**} is permitAll in the security chain.
 * So the client presents a short-lived signed token on CONNECT and this principal
 * is built from its claims. {@code getName()} returns the user id because that is
 * what user-destination routing ({@code /user/queue/...}) keys on, and usernames
 * are not guaranteed stable across the two services.
 */
public class CallUserPrincipal implements Principal {

    private final Long userId;
    private final String username;
    private final String displayName;
    private final Set<String> authorities;
    private final Long clientId;

    public CallUserPrincipal(Long userId, String username, String displayName,
            Set<String> authorities, Long clientId) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.authorities = authorities == null ? Collections.emptySet() : authorities;
        this.clientId = clientId;
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }

    public Set<String> getAuthorities() {
        return authorities;
    }

    public Long getClientId() {
        return clientId;
    }

    public boolean isBillOpsAdmin() {
        return authorities.contains("ROLE_BILLOPS_ADMIN") || authorities.contains("ROLE_BILLOPS_ADMIN_SUB");
    }
}
