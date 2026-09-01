package com.xammer.billops.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xammer.cloud.domain.Invoice;
import lombok.NoArgsConstructor;
import java.util.List;
import jakarta.persistence.*;

@Entity
@Table(name = "cloud_account")
@NoArgsConstructor
public class CloudAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Column(name = "aws_account_id", unique = true)
    private String awsAccountId;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "access_type")
    private String accessType;

    @Column(name = "role_arn", unique = true)
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
    private String gcpProjectId;

    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "billing_export_table")
    private String billingExportTable;

    @Column(name = "azure_tenant_id")
    private String azureTenantId;

    @Column(name = "azure_subscription_id")
    private String azureSubscriptionId;

    @Column(name = "azure_client_id")
    private String azureClientId;

    @Column(name = "azure_client_secret")
    private String azureClientSecret;

    @Column(name = "azure_billing_rg")
    private String azureBillingRg;

    @Column(name = "azure_billing_storage_account")
    private String azureBillingStorageAccount;

    @Column(name = "azure_billing_container")
    private String azureBillingContainer;

    @Column(name = "azure_billing_directory")
    private String azureBillingDirectory;

    @Column(name = "cur_s3_bucket")
    private String curS3Bucket;

    @Column(name = "cur_report_path")
    private String curReportPath;

    @Column(name = "grafana_ip")
    private String grafanaIp;

    /**
     * ISO-4217 billing currency code for this account (e.g. "INR", "USD").
     * Null / absent means USD (no conversion needed).
     */
    @Column(name = "billing_currency")
    private String billingCurrency;

    /**
     * How many units of billingCurrency equal 1 USD.
     * e.g. 84.0 when billingCurrency = "INR" (₹84 = $1).
     * Null / 0 / 1.0 is treated as no conversion needed.
     */
    @Column(name = "usd_exchange_rate")
    private Double usdExchangeRate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnore
    private Client client;

    @OneToMany(mappedBy = "cloudAccount", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Invoice> invoices;

    // public CloudAccount() {
    // }

    public CloudAccount(String accountName, String externalId, String accessType, Client client) {
        this.accountName = accountName;
        this.externalId = externalId;
        this.accessType = accessType;
        this.client = client;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getAwsAccountId() {
        return awsAccountId;
    }

    public void setAwsAccountId(String awsAccountId) {
        this.awsAccountId = awsAccountId;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getAccessType() {
        return accessType;
    }

    public void setAccessType(String accessType) {
        this.accessType = accessType;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getGcpServiceAccountKey() {
        return gcpServiceAccountKey;
    }

    public void setGcpServiceAccountKey(String gcpServiceAccountKey) {
        this.gcpServiceAccountKey = gcpServiceAccountKey;
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

    public String getGcpProjectId() {
        return gcpProjectId;
    }

    public void setGcpProjectId(String gcpProjectId) {
        this.gcpProjectId = gcpProjectId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBillingExportTable() {
        return billingExportTable;
    }

    public void setBillingExportTable(String billingExportTable) {
        this.billingExportTable = billingExportTable;
    }

    public String getAzureTenantId() {
        return azureTenantId;
    }

    public void setAzureTenantId(String azureTenantId) {
        this.azureTenantId = azureTenantId;
    }

    public String getAzureSubscriptionId() {
        return azureSubscriptionId;
    }

    public void setAzureSubscriptionId(String azureSubscriptionId) {
        this.azureSubscriptionId = azureSubscriptionId;
    }

    public String getAzureClientId() {
        return azureClientId;
    }

    public void setAzureClientId(String azureClientId) {
        this.azureClientId = azureClientId;
    }

    public String getAzureClientSecret() {
        return azureClientSecret;
    }

    public void setAzureClientSecret(String azureClientSecret) {
        this.azureClientSecret = azureClientSecret;
    }

    public String getAzureBillingRg() {
        return azureBillingRg;
    }

    public void setAzureBillingRg(String azureBillingRg) {
        this.azureBillingRg = azureBillingRg;
    }

    public String getAzureBillingStorageAccount() {
        return azureBillingStorageAccount;
    }

    public void setAzureBillingStorageAccount(String azureBillingStorageAccount) {
        this.azureBillingStorageAccount = azureBillingStorageAccount;
    }

    public String getAzureBillingContainer() {
        return azureBillingContainer;
    }

    public void setAzureBillingContainer(String azureBillingContainer) {
        this.azureBillingContainer = azureBillingContainer;
    }

    public String getAzureBillingDirectory() {
        return azureBillingDirectory;
    }

    public void setAzureBillingDirectory(String azureBillingDirectory) {
        this.azureBillingDirectory = azureBillingDirectory;
    }

    public String getCurS3Bucket() {
        return curS3Bucket;
    }

    public void setCurS3Bucket(String curS3Bucket) {
        this.curS3Bucket = curS3Bucket;
    }

    public String getCurReportPath() {
        return curReportPath;
    }

    public void setCurReportPath(String curReportPath) {
        this.curReportPath = curReportPath;
    }

    public String getGrafanaIp() {
        return grafanaIp;
    }

    public void setGrafanaIp(String grafanaIp) {
        this.grafanaIp = grafanaIp;
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public List<Invoice> getInvoices() {
        return invoices;
    }

    public void setInvoices(List<Invoice> invoices) {
        this.invoices = invoices;
    }

    public String getBillingCurrency() {
        return billingCurrency;
    }

    public void setBillingCurrency(String billingCurrency) {
        this.billingCurrency = billingCurrency;
    }

    public Double getUsdExchangeRate() {
        return usdExchangeRate;
    }

    public void setUsdExchangeRate(Double usdExchangeRate) {
        this.usdExchangeRate = usdExchangeRate;
    }
}