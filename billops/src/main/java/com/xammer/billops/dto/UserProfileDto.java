package com.xammer.billops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class UserProfileDto {
    private Long id;
    private String username;
    private String email;
    private List<String> roles;
    private List<String> allowedTabs;
    private boolean isPrimaryUser;

    public UserProfileDto(String username, List<String> roles) {
        this.username = username;
        this.roles = roles;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }
    public List<String> getAllowedTabs() { return allowedTabs; }
    public void setAllowedTabs(List<String> allowedTabs) { this.allowedTabs = allowedTabs; }
    @JsonProperty("isPrimaryUser")
    public boolean isPrimaryUser() { return isPrimaryUser; }
    public void setPrimaryUser(boolean isPrimaryUser) { this.isPrimaryUser = isPrimaryUser; }
}