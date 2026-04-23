package com.xammer.cloud.dto.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a user returned by the Authentik core/users API.
 *
 * Authentik API reference:
 *   GET /api/v3/core/users/
 *   https://docs.goauthentik.io/developer-docs/api/
 *
 * Key Authentik invariant enforced by this app:
 *   username MUST equal email — required for SAML/SCIM attribute mapping.
 *   All create calls set username = email.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthentikUserDto {

    /** Authentik integer primary key for this user. Used for group membership calls. */
    @JsonProperty("pk")
    private Long pk;

    /** Always equal to email in this platform. */
    @JsonProperty("username")
    private String username;

    @JsonProperty("name")
    private String name;

    @JsonProperty("email")
    private String email;

    @JsonProperty("is_active")
    private boolean isActive;

    @JsonProperty("last_login")
    private String lastLogin;

    /**
     * Groups the user belongs to.
     * Populated by Authentik when groups_obj is requested via the API.
     */
    @JsonProperty("groups_obj")
    private List<GroupRef> groupsObj;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GroupRef {
        @JsonProperty("pk")
        private String pk;
        @JsonProperty("name")
        private String name;
    }
}
