package com.xammer.cloud.dto;

import java.util.List;

public class UserProfileDto {
    private Long id;
    private String username;
    private List<String> roles;
    private boolean tourSeen;

    public UserProfileDto(String username, List<String> roles, boolean tourSeen) {
        this.username = username;
        this.roles = roles;
        this.tourSeen = tourSeen;
    }

    // Getters and Setters
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
    
    public List<String> getRoles() { 
        return roles; 
    }
    
    public void setRoles(List<String> roles) { 
        this.roles = roles; 
    }

    public boolean isTourSeen() { return tourSeen; }
    public void setTourSeen(boolean tourSeen) { this.tourSeen = tourSeen; }

}