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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CloudAccountInfo {
        private Long id;
        private String accountName;
        private String provider;
    }
}
