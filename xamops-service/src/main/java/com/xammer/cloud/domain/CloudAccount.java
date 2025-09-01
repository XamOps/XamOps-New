package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.persistence.*;

@Entity
public class CloudAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String accountName;

    @Column(unique = true)
    private String awsAccountId;

    @Column
    private String externalId;

    @Column
    private final String accessType;

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
    private String provider; // AWS or GCP

    // ✅ ADD THIS FIELD FOR GCP BILLING
    @Column
    private String billingExportTable; 

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
        public Long getId() { return id; }
        public String getAccountName() { return accountName; }
        public String getAwsAccountId() { return awsAccountId; }
        public String getExternalId() { return externalId; }
        public String getAccessType() { return accessType; }
        public String getRoleArn() { return roleArn; }
        public String getGcpServiceAccountKey() { return gcpServiceAccountKey; }
        public String getGcpWorkloadIdentityPoolId() { return gcpWorkloadIdentityPoolId; }
        public String getGcpWorkloadIdentityProviderId() { return gcpWorkloadIdentityProviderId; }
        public String getGcpServiceAccountEmail() { return gcpServiceAccountEmail; }
        public String getGcpProjectId() { return gcpProjectId; }
        public String getStatus() { return status; }
        public String getProvider() { return provider; }
        public String getBillingExportTable() { return billingExportTable; }
        public Client getClient() { return client; }
}