package com.xammer.billops.dto;

public class TenantDto {
    private String tenantId;
    private String companyName;
    
    public TenantDto(String tenantId, String companyName) {
        this.tenantId = tenantId;
        this.companyName = companyName;
    }
    
    // Getters and Setters
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
}