package com.xammer.cloud.dto.auth;

/**
 * Verified identity extracted from a Google OpenID Connect ID token.
 * Transient — never persisted.
 */
public class GoogleIdentity {

    private final String sub;          // Google's permanent unique subject ID
    private final String email;
    private final boolean emailVerified;
    private final String name;
    private final String picture;

    public GoogleIdentity(String sub, String email, boolean emailVerified, String name, String picture) {
        this.sub = sub;
        this.email = email;
        this.emailVerified = emailVerified;
        this.name = name;
        this.picture = picture;
    }

    public String getSub() { return sub; }
    public String getEmail() { return email; }
    public boolean isEmailVerified() { return emailVerified; }
    public String getName() { return name; }
    public String getPicture() { return picture; }
}
