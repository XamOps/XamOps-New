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
    private boolean primaryUser;

    private List<CloudAccountInfo> cloudAccounts;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public boolean isPrimaryUser() {
        return primaryUser;
    }

    public void setPrimaryUser(boolean primaryUser) {
        this.primaryUser = primaryUser;
    }

    public List<CloudAccountInfo> getCloudAccounts() {
        return cloudAccounts;
    }

    public void setCloudAccounts(List<CloudAccountInfo> cloudAccounts) {
        this.cloudAccounts = cloudAccounts;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CloudAccountInfo {
        private Long id;
        private String accountId;
        private String accountName;
        private String provider;

        // public CloudAccountInfo() {
        // }

        public CloudAccountInfo(Long id, String accountId, String accountName) {
            this.id = id;
            this.accountId = accountId;
            this.accountName = accountName;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(String accountName) {
            this.accountName = accountName;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }
}
