package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileDTO {
    private Long id;
    private String username;
    private String email;
    private String role;
    private String clientName;

    @JsonProperty("isPrimaryUser")
    private boolean primaryUser; // true if createdBy is null (can create team users)

    private List<CloudAccountInfo> cloudAccounts;

    // Sidebar tab keys this user is allowed to see. Empty/null = no restriction (full sidebar).
    private List<String> allowedTabs;

    /**
     * Whether this user's organisation holds the Matters.ai DSPM add-on.
     *
     * Carried on the profile rather than fetched separately because the sidebar already requests this
     * endpoint on every page load, and it needs the answer to decide whether to render the DSPM group
     * under XamSecure. A dedicated call would add a request to every navigation to answer a question
     * this response was already going to be sent for.
     *
     * Nav visibility only. The real enforcement is server-side in
     * {@code MattersCredentialResolver}, which checks the same flag before any credential is resolved —
     * so a user who deep-links past the hidden nav still gets a 403.
     */
    private boolean mattersDspmEnabled;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CloudAccountInfo {
        private Long id;
        private String accountName;
        private String provider;
    }
}
