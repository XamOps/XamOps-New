package com.xammer.cloud.dto.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a group returned by the Authentik core/groups API.
 *
 * Authentik API reference:
 *   GET /api/v3/core/groups/?name__startswith={prefix}
 *
 * The AccessOps Groups page enriches this DTO with AWS account context
 * from the client_aws_access table before returning to the frontend.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthentikGroupDto {

    /** UUID string primary key of the group in Authentik. */
    @JsonProperty("pk")
    private String pk;

    @JsonProperty("name")
    private String name;

    /** Number of users currently in this group (from Authentik num_pk). */
    @JsonProperty("num_pk")
    private int numPk;

    /** List of user PKs (integers) currently in this group. */
    @JsonProperty("users")
    private List<Long> users;

    /** Lightweight user objects in this group. */
    @JsonProperty("users_obj")
    private List<UserRef> usersObj;

    // ── Enriched by XamOps backend (not from Authentik) ──────────────────────
    private String awsAccountId;
    private String awsAccountName;
    private String permissionSetName;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserRef {
        @JsonProperty("pk")
        private Long pk;
        @JsonProperty("username")
        private String username;
        @JsonProperty("name")
        private String name;
        @JsonProperty("email")
        private String email;
        @JsonProperty("is_active")
        private boolean isActive;
    }
}
