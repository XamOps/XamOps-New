package com.xammer.billops.dto;

import java.util.List;

public class UserProfileDto {
    private String username;
    private List<String> roles;

    public UserProfileDto(String username, List<String> roles) {
        this.username = username;
        this.roles = roles;
    }

    // Getters and Setters
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }
}