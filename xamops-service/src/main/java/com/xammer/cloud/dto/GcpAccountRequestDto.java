package com.xammer.cloud.dto;

public class GcpAccountRequestDto {

    private String accountName;
    private String gcpProjectId;
    private String gcpWorkloadIdentityPoolId;
    private String gcpWorkloadIdentityProviderId;
    private String gcpServiceAccountEmail;
    private String serviceAccountKey;
    private String projectId;
    private String billingExportTable;
    /** OWN_EXPORT, XAMMER_CSP_DATASET or XAMMER_EXPORT. Falls back to cspManaged when absent. */
    private String billingSource;
    private Boolean cspManaged;
    private Long cspBillingConfigId;
    private String accessType;

    // Getters and Setters
    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getGcpProjectId() {
        return gcpProjectId;
    }

    public void setGcpProjectId(String gcpProjectId) {
        this.gcpProjectId = gcpProjectId;
    }

    public String getGcpWorkloadIdentityPoolId() {
        return gcpWorkloadIdentityPoolId;
    }

    public void setGcpWorkloadIdentityPoolId(String gcpWorkloadIdentityPoolId) {
        this.gcpWorkloadIdentityPoolId = gcpWorkloadIdentityPoolId;
    }

    public String getGcpWorkloadIdentityProviderId() {
        return gcpWorkloadIdentityProviderId;
    }

    public void setGcpWorkloadIdentityProviderId(String gcpWorkloadIdentityProviderId) {
        this.gcpWorkloadIdentityProviderId = gcpWorkloadIdentityProviderId;
    }

    public String getGcpServiceAccountEmail() {
        return gcpServiceAccountEmail;
    }

    public void setGcpServiceAccountEmail(String gcpServiceAccountEmail) {
        this.gcpServiceAccountEmail = gcpServiceAccountEmail;
    }

    public String getServiceAccountKey() {
        return serviceAccountKey;
    }

    public void setServiceAccountKey(String serviceAccountKey) {
        this.serviceAccountKey = serviceAccountKey;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getBillingExportTable() {
        return billingExportTable;
    }

    public void setBillingExportTable(String billingExportTable) {
        this.billingExportTable = billingExportTable;
    }

    public String getBillingSource() {
        return billingSource;
    }

    public void setBillingSource(String billingSource) {
        this.billingSource = billingSource;
    }

    public Boolean getCspManaged() {
        return cspManaged;
    }

    public void setCspManaged(Boolean cspManaged) {
        this.cspManaged = cspManaged;
    }

    public Long getCspBillingConfigId() {
        return cspBillingConfigId;
    }

    public void setCspBillingConfigId(Long cspBillingConfigId) {
        this.cspBillingConfigId = cspBillingConfigId;
    }

    public String getAccessType() {
        return accessType;
    }

    public void setAccessType(String accessType) {
        this.accessType = accessType;
    }
}
