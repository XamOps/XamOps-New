package com.xammer.cloud.dto;

import lombok.Data;

@Data
public class CreateUserRequest {
    private String username;
    private String password;
    private String email;
    private String role;      // e.g., ROLE_ADMIN, ROLE_USER
    private String tenantId;  // Target tenant to create the user in
}