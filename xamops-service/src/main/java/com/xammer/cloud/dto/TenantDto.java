package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TenantDto {
    private String tenantId;
    private String companyName;

    public TenantDto(String tenantId, String companyName) {
        this.tenantId = tenantId;
        this.companyName = companyName;
    }

    // Getters and Setters
    @JsonProperty("id")
    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    @JsonProperty("name")
    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }
}