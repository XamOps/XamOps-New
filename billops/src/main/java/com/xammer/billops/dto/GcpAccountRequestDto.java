package com.xammer.billops.dto;

import lombok.Data;

@Data
public class GcpAccountRequestDto {
    private String accountName;
    private String projectId;
    private String gcpProjectId;
    private String gcpProjectName;
    private String gcpWorkloadIdentityPoolId;
    private String gcpWorkloadIdentityProviderId;
    private String gcpServiceAccountEmail;
    private String serviceAccountKey;
    private String billingExportDatasetId;
    private String billingExportTable;

    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getGcpProjectId() { return gcpProjectId; }
    public void setGcpProjectId(String gcpProjectId) { this.gcpProjectId = gcpProjectId; }
    public String getGcpProjectName() { return gcpProjectName; }
    public void setGcpProjectName(String gcpProjectName) { this.gcpProjectName = gcpProjectName; }
    public String getGcpWorkloadIdentityPoolId() { return gcpWorkloadIdentityPoolId; }
    public void setGcpWorkloadIdentityPoolId(String gcpWorkloadIdentityPoolId) { this.gcpWorkloadIdentityPoolId = gcpWorkloadIdentityPoolId; }
    public String getGcpWorkloadIdentityProviderId() { return gcpWorkloadIdentityProviderId; }
    public void setGcpWorkloadIdentityProviderId(String gcpWorkloadIdentityProviderId) { this.gcpWorkloadIdentityProviderId = gcpWorkloadIdentityProviderId; }
    public String getGcpServiceAccountEmail() { return gcpServiceAccountEmail; }
    public void setGcpServiceAccountEmail(String gcpServiceAccountEmail) { this.gcpServiceAccountEmail = gcpServiceAccountEmail; }
    public String getServiceAccountKey() { return serviceAccountKey; }
    public void setServiceAccountKey(String serviceAccountKey) { this.serviceAccountKey = serviceAccountKey; }
    public String getBillingExportDatasetId() { return billingExportDatasetId; }
    public void setBillingExportDatasetId(String billingExportDatasetId) { this.billingExportDatasetId = billingExportDatasetId; }
    public String getBillingExportTable() { return billingExportTable; }
    public void setBillingExportTable(String billingExportTable) { this.billingExportTable = billingExportTable; }
}