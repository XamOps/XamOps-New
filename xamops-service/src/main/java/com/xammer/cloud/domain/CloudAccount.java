package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

@Entity
@Data
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
    private String status = "PENDING"; // PENDING, CONNECTED, FAILED

    @Column(nullable = false)
    private String provider; // AWS, GCP, or Azure

    @Column
    private String billingExportTable;

    @Column(name = "gcp_billing_account_id")
    private String gcpBillingAccountId;


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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnore
    private Client client;

    @Column(name = "grafana_ip")
    private String grafanaIp;

    // Also, add the getter and setter for this new field
    public String getGrafanaIp() {
        return grafanaIp;
    }

    public void setGrafanaIp(String grafanaIp) {
        this.grafanaIp = grafanaIp;
    }

    public CloudAccount(String accountName, String externalId, String accessType, Client client) {
        this.accountName = accountName;
        this.externalId = externalId;
        this.accessType = accessType;
        this.client = client;
    }

    // ✅ ADD THIS METHOD
    // This helper method returns the correct account ID based on the provider.
    public String getProviderAccountId() {
        switch (this.provider) {
            case "AWS":
                return this.awsAccountId;
            case "GCP":
                return this.gcpProjectId;
            case "Azure":
                return this.azureSubscriptionId;
            default:
                return null;
        }
    }

}