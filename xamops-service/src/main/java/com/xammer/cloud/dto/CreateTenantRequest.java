package com.xammer.cloud.dto;

import lombok.Data;

@Data
public class CreateTenantRequest {
    private String tenantId;
    private String companyName;
    private String dbUrl;
    private String dbUsername;
    private String dbPassword;
    private String driverClassName = "org.postgresql.Driver"; // Default
    private boolean active = true;
}