package com.xammer.cloud.dto.identity;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for add-user-to-group and remove-user-from-group endpoints.
 *
 * Authentik API calls made by IdentityService:
 *   Add:    POST /api/v3/core/groups/{groupId}/add_user/    { "pk": userId }
 *   Remove: POST /api/v3/core/groups/{groupId}/remove_user/ { "pk": userId }
 *
 * Adding a user to an Authentik group automatically grants AWS SSO access
 * via the configured SCIM provider (sync within seconds, no extra API call).
 * Removing revokes access the same way.
 */
@Data
@NoArgsConstructor
public class GroupMembershipRequest {

    /** Authentik integer pk of the user to add or remove. */
    @NotNull
    private Long userId;
}
