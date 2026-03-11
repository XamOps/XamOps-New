package com.xammer.billops.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

public class TeamUserDTO {
    private Long id;
    private String username;
    private String email;
    private String role;
    private String password; // Only used for create/update, never returned
    private List<Long> accountIds; // Cloud account IDs this user can access
    private List<ProfileDTO.CloudAccountInfo> cloudAccounts; // Full cloud account details for display
    private List<String> allowedTabs; // Tab keys this user can access (ROLE_BILLOPS_ADMIN sub-users)
    private boolean allAccountsAccess; // If true, user sees all accounts dynamically (no fixed list)

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public List<Long> getAccountIds() { return accountIds; }
    public void setAccountIds(List<Long> accountIds) { this.accountIds = accountIds; }
    public List<ProfileDTO.CloudAccountInfo> getCloudAccounts() { return cloudAccounts; }
    public void setCloudAccounts(List<ProfileDTO.CloudAccountInfo> cloudAccounts) { this.cloudAccounts = cloudAccounts; }
    public List<String> getAllowedTabs() { return allowedTabs; }
    public void setAllowedTabs(List<String> allowedTabs) { this.allowedTabs = allowedTabs; }
    public boolean isAllAccountsAccess() { return allAccountsAccess; }
    public void setAllAccountsAccess(boolean allAccountsAccess) { this.allAccountsAccess = allAccountsAccess; }
}
