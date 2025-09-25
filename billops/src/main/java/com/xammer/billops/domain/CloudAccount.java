package com.xammer.billops.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor
public class CloudAccount {

@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "cloud_account_seq_gen")
@SequenceGenerator(name = "cloud_account_seq_gen", sequenceName = "cloud_account_id_seq", allocationSize = 1)
private Long id;

    @Column(nullable = false)
    private String accountName;

    @Column(unique = true)
    private String awsAccountId;

    @Column
    private String externalId;

    @Column
    private String accessType;

    @Column(unique = true)
    private String roleArn;

    @Column(columnDefinition = "TEXT")
    private String gcpServiceAccountKey;

    @Column
    private String gcpWorkloadIdentityPoolId;

    @Column
    private String gcpWorkloadIdentityProviderId;

    @Column
    private String gcpServiceAccountEmail;

    @Column
    private String gcpProjectId;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(nullable = false)
    private String provider;

    @Column
    private String billingExportTable;

    @Column(name = "azure_tenant_id")
    private String azureTenantId;

    @Column(name = "azure_subscription_id")
    private String azureSubscriptionId;

    @Column(name = "azure_client_id")
    private String azureClientId;

    @Column(name = "azure_client_secret")
    private String azureClientSecret;

    @Column(name = "cur_s3_bucket")
    private String curS3Bucket;

    @Column(name = "cur_report_path")
    private String curReportPath;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnore
    private Client client;

    public CloudAccount(String accountName, String externalId, String accessType, Client client) {
        this.accountName = accountName;
        this.externalId = externalId;
        this.accessType = accessType;
        this.client = client;
    }
    
    // --- Manually Added Getters and Setters to fix compilation ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getAwsAccountId() { return awsAccountId; }
    public void setAwsAccountId(String awsAccountId) { this.awsAccountId = awsAccountId; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getAccessType() { return accessType; }
    public void setAccessType(String accessType) { this.accessType = accessType; }
    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }
    public String getGcpServiceAccountKey() { return gcpServiceAccountKey; }
    public void setGcpServiceAccountKey(String gcpServiceAccountKey) { this.gcpServiceAccountKey = gcpServiceAccountKey; }
    public String getGcpWorkloadIdentityPoolId() { return gcpWorkloadIdentityPoolId; }
    public void setGcpWorkloadIdentityPoolId(String gcpWorkloadIdentityPoolId) { this.gcpWorkloadIdentityPoolId = gcpWorkloadIdentityPoolId; }
    public String getGcpWorkloadIdentityProviderId() { return gcpWorkloadIdentityProviderId; }
    public void setGcpWorkloadIdentityProviderId(String gcpWorkloadIdentityProviderId) { this.gcpWorkloadIdentityProviderId = gcpWorkloadIdentityProviderId; }
    public String getGcpServiceAccountEmail() { return gcpServiceAccountEmail; }
    public void setGcpServiceAccountEmail(String gcpServiceAccountEmail) { this.gcpServiceAccountEmail = gcpServiceAccountEmail; }
    public String getGcpProjectId() { return gcpProjectId; }
    public void setGcpProjectId(String gcpProjectId) { this.gcpProjectId = gcpProjectId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBillingExportTable() { return billingExportTable; }
    public void setBillingExportTable(String billingExportTable) { this.billingExportTable = billingExportTable; }
    public String getAzureTenantId() { return azureTenantId; }
    public void setAzureTenantId(String azureTenantId) { this.azureTenantId = azureTenantId; }
    public String getAzureSubscriptionId() { return azureSubscriptionId; }
    public void setAzureSubscriptionId(String azureSubscriptionId) { this.azureSubscriptionId = azureSubscriptionId; }
    public String getAzureClientId() { return azureClientId; }
    public void setAzureClientId(String azureClientId) { this.azureClientId = azureClientId; }
    public String getAzureClientSecret() { return azureClientSecret; }
    public void setAzureClientSecret(String azureClientSecret) { this.azureClientSecret = azureClientSecret; }
    public String getCurS3Bucket() { return curS3Bucket; }
    public void setCurS3Bucket(String curS3Bucket) { this.curS3Bucket = curS3Bucket; }
    public String getCurReportPath() { return curReportPath; }
    public void setCurReportPath(String curReportPath) { this.curReportPath = curReportPath; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }

}