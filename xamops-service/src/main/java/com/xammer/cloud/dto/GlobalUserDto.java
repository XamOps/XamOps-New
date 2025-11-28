package com.xammer.cloud.dto;

public class GlobalUserDto {
    private String username;
    private String tenantId;
    private String role;
    private boolean enabled;

    public GlobalUserDto(String username, String tenantId, String role, boolean enabled) {
        this.username = username;
        this.tenantId = tenantId;
        this.role = role;
        this.enabled = enabled;
    }

    // Getters and Setters
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}