package com.xammer.cloud.dto.identity;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Frontend request body for inviting a new user.
 *
 * Flow executed by IdentityService:
 *   1. POST /api/v3/core/users/  { username: email, email, name, is_active: true }
 *   2. POST /api/v3/core/groups/{groupId}/add_user/  { pk: newUserId }
 *   3. POST /api/v3/core/users/{newUserId}/recovery_email/
 *      → Authentik sends a password-setup link; user self-serves password.
 *
 * Per Authentik best practice: username always equals email for SSO/SCIM attribute mapping.
 */
@Data
@NoArgsConstructor
public class InviteUserRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Email
    private String email;

    /** Authentik group UUID (pk) the new user should be added to. */
    @NotBlank
    private String groupId;
}
