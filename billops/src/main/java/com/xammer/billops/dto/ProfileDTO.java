package com.xammer.billops.dto;

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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CloudAccountInfo {
        private Long id;
        private String name;
        private String provider;
    }
}
