package com.xammer.billops.dto;

import lombok.Data;

@Data
public class CreateUserRequest {
    private String username;
    private String password;
    private String email;
    private String role;
    private Long clientId; // Links user to a specific Client
}