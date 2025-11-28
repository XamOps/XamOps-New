package com.xammer.billops.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xammer.cloud.domain.Invoice;

import lombok.NoArgsConstructor;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

@Entity
@Table(name = "cloud_account")
@NoArgsConstructor
public class CloudAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "cloud_account_seq_gen")
    @SequenceGenerator(name = "cloud_account_seq_gen", sequenceName = "cloud_account_id_seq", allocationSize = 1)
    @JsonProperty("id")
    private Long id;

    @Column(name = "account_name", nullable = false)
    @JsonProperty("accountName")
    private String accountName;

    @Column(name = "aws_account_id", unique = true)
    @JsonProperty("awsAccountId")
    private String awsAccountId;

    @Column(name = "external_id")
    @JsonProperty("externalId")
    private String externalId;

    @Column(name = "access_type")
    @JsonProperty("accessType")
    private String accessType;

    @Column(name = "role_arn", unique = true)
    @JsonProperty("roleArn")
    private String roleArn;

    @Column(name = "gcp_service_account_key", columnDefinition = "TEXT")
    private String gcpServiceAccountKey;

    @Column(name = "gcp_workload_identity_pool_id")
    private String gcpWorkloadIdentityPoolId;

    @Column(name = "gcp_workload_identity_provider_id")
    private String gcpWorkloadIdentityProviderId;

    @Column(name = "gcp_service_account_email")
    private String gcpServiceAccountEmail;

    @Column(name = "gcp_project_id")
    @JsonProperty("gcpProjectId")
    private String gcpProjectId;

    @Column(name = "status", nullable = false)
    @JsonProperty("status")
    private String status = "PENDING";

    @Column(name = "provider", nullable = false)
    @JsonProperty("provider")
    private String provider;

    @Column(name = "billing_export_table")
    private String billingExportTable;

    // --- Azure Credentials ---
    @Column(name = "azure_tenant_id")
    @JsonProperty("azureTenantId")
    private String azureTenantId;

    @Column(name = "azure_subscription_id")
    @JsonProperty("azureSubscriptionId")
    private String azureSubscriptionId;

    @Column(name = "azure_client_id")
    @JsonProperty("azureClientId")
    private String azureClientId;

    @Column(name = "azure_client_secret")
    private String azureClientSecret;

    // --- Azure Billing Export Configuration ---
    @Column(name = "azure_billing_rg")
    @JsonProperty("azureBillingRg")
    private String azureBillingRg;

    @Column(name = "azure_billing_storage_account")
    @JsonProperty("azureBillingStorageAccount")
    private String azureBillingStorageAccount;

    @Column(name = "azure_billing_container")
    @JsonProperty("azureBillingContainer")
    private String azureBillingContainer;

    @Column(name = "azure_billing_directory")
    @JsonProperty("azureBillingDirectory")
    private String azureBillingDirectory;

    @Column(name = "cur_s3_bucket")
    private String curS3Bucket;

    @Column(name = "cur_report_path")
    private String curReportPath;

    // --- ADDED: Monitoring IP ---
    @Column(name = "grafana_ip")
    @JsonProperty("grafanaIp")
    private String grafanaIp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnore
    private Client client;
    
    @OneToMany(mappedBy = "cloudAccount", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Invoice> invoices;

    public CloudAccount(String accountName, String externalId, String accessType, Client client) {
        this.accountName = accountName;
        this.externalId = externalId;
        this.accessType = accessType;
        this.client = client;
    }
    
    // --- Helper Methods Exposed as JSON ---

    @JsonProperty("providerAccountId")
    public String getProviderAccountId() {
        if ("AWS".equalsIgnoreCase(this.provider)) return this.awsAccountId;
        if ("GCP".equalsIgnoreCase(this.provider)) return this.gcpProjectId;
        if ("Azure".equalsIgnoreCase(this.provider)) return this.azureSubscriptionId;
        return null;
    }

    @JsonProperty("dbId")
    public Long getDbId() {
        return this.id;
    }

    // --- Getters and Setters ---
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

    public String getAzureBillingRg() { return azureBillingRg; }
    public void setAzureBillingRg(String azureBillingRg) { this.azureBillingRg = azureBillingRg; }

    public String getAzureBillingStorageAccount() { return azureBillingStorageAccount; }
    public void setAzureBillingStorageAccount(String azureBillingStorageAccount) { this.azureBillingStorageAccount = azureBillingStorageAccount; }

    public String getAzureBillingContainer() { return azureBillingContainer; }
    public void setAzureBillingContainer(String azureBillingContainer) { this.azureBillingContainer = azureBillingContainer; }

    public String getAzureBillingDirectory() { return azureBillingDirectory; }
    public void setAzureBillingDirectory(String azureBillingDirectory) { this.azureBillingDirectory = azureBillingDirectory; }

    public String getCurS3Bucket() { return curS3Bucket; }
    public void setCurS3Bucket(String curS3Bucket) { this.curS3Bucket = curS3Bucket; }
    
    public String getCurReportPath() { return curReportPath; }
    public void setCurReportPath(String curReportPath) { this.curReportPath = curReportPath; }
    
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }

    // --- ADDED: Getter and Setter for Grafana IP ---
    public String getGrafanaIp() { return grafanaIp; }
    public void setGrafanaIp(String grafanaIp) { this.grafanaIp = grafanaIp; }
}