package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xammer.cloud.domain.converter.EncryptedStringConverter;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

import jakarta.persistence.*;

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

    /**
     * GCP service-account JSON key. A standing credential with no expiry, so it is encrypted
     * at rest — see {@link EncryptedStringConverter}. {@code @JsonIgnore} because this entity
     * is serialised directly by several account endpoints and the key must never leave the
     * service in a response body.
     */
    @JsonIgnore
    @Convert(converter = EncryptedStringConverter.class)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_source", length = 32)
    private BillingSource billingSource;

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

    /**
     * Azure service-principal client secret. Same reasoning as
     * {@link #gcpServiceAccountKey}: long-lived, so encrypted at rest, and never serialised
     * back to a caller. The report noted this value was being echoed in an API response —
     * {@code @JsonIgnore} closes that off at the entity rather than per-endpoint.
     */
    @JsonIgnore
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "azure_client_secret")
    private String azureClientSecret;

    @Column(name = "azure_billing_rg")
    private String azureBillingRg;

    /**
     * Account-scoped agent credential, for agents that are not tied to a Kubernetes cluster
     * (disk and database agents on VMs). Clusters keep their own key on
     * {@code kubernetes_cluster_config.agent_api_key}.
     *
     * {@code @JsonIgnore} because this entity is serialised directly by several account
     * endpoints — without it, a credential would be returned to the browser by any response
     * that happens to include a CloudAccount.
     */
    @JsonIgnore
    @Column(name = "agent_api_key", length = 64)
    private String agentApiKey;

    @Column(name = "agent_key_issued_at")
    private Instant agentKeyIssuedAt;

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

    /**
     * Billing source is authoritative; {@code cspManaged} is derived here rather than set
     * independently. The reseller read path
     * ({@code CloudAccountRepository#findCspBillingConfigByGcpProjectId}) and the nightly
     * ingestion job both still select on {@code csp_managed}, so writing the two in one
     * place is what stops them drifting — and drift is exactly what sent a Xammer-billed
     * project with its own export table to the reseller dataset, where it read zero.
     */
    public void setBillingSource(BillingSource billingSource) {
        this.billingSource = billingSource;
        this.cspManaged = billingSource != null && billingSource.usesCspDataset();
    }

    /**
     * Billing source for accounts predating the column, where it is still null: fall back to
     * what {@code cspManaged} used to mean on its own.
     */
    public BillingSource resolveBillingSource() {
        if (this.billingSource != null) {
            return this.billingSource;
        }
        return this.cspManaged ? BillingSource.XAMMER_CSP_DATASET : BillingSource.OWN_EXPORT;
    }

}