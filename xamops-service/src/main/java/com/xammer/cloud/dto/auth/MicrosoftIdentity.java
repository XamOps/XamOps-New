package com.xammer.cloud.dto.auth;

/**
 * Verified identity extracted from a Microsoft (Entra ID / Azure AD) OpenID
 * Connect ID token. Transient — never persisted.
 */
public class MicrosoftIdentity {

    private final String sub;          // Microsoft's permanent unique subject ID (per app+user)
    private final String email;
    private final boolean emailVerified;
    private final String name;

    public MicrosoftIdentity(String sub, String email, boolean emailVerified, String name) {
        this.sub = sub;
        this.email = email;
        this.emailVerified = emailVerified;
        this.name = name;
    }

    public String getSub() { return sub; }
    public String getEmail() { return email; }
    public boolean isEmailVerified() { return emailVerified; }
    public String getName() { return name; }
}
