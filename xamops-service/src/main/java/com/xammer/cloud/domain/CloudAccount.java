package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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

    @Column(name = "csp_managed", nullable = false)
    private boolean cspManaged = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "csp_billing_config_id")
    private CspBillingConfig cspBillingConfig;

    @Column(name = "billing_data_available")
    private Boolean billingDataAvailable;

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

    @Column(name = "grafana_dashboard_uid")
    private String grafanaDashboardUid;

    @Column(name = "grafana_dashboard_slug")
    private String grafanaDashboardSlug;

    @Column(name = "last_sync")
    private Instant lastSync;

    // Getter and setter for awsAccountId
    public String getAwsAccountId() {
        return awsAccountId;
    }

    public void setAwsAccountId(String awsAccountId) {
        this.awsAccountId = awsAccountId;
    }

    // Getter and setter for grafanaIp
    public String getGrafanaIp() {
        return grafanaIp;
    }

    public void setGrafanaIp(String grafanaIp) {
        this.grafanaIp = grafanaIp;
    }

    // Getter and setter for grafanaDashboardUid
    public String getGrafanaDashboardUid() {
        return grafanaDashboardUid;
    }

    public void setGrafanaDashboardUid(String grafanaDashboardUid) {
        this.grafanaDashboardUid = grafanaDashboardUid;
    }

    // Getter and setter for grafanaDashboardSlug
    public String getGrafanaDashboardSlug() {
        return grafanaDashboardSlug;
    }

    public void setGrafanaDashboardSlug(String grafanaDashboardSlug) {
        this.grafanaDashboardSlug = grafanaDashboardSlug;
    }

    public CloudAccount(String accountName, String externalId, String accessType, Client client) {
        this.accountName = accountName;
        this.externalId = externalId;
        this.accessType = accessType;
        this.client = client;
    }

    // This helper method returns the correct account ID based on the provider.
    public String getProviderAccountId() {
        if (this.provider == null) {
            return null;
        }
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