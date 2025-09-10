package com.xammer.billops.domain;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*; // Changed from jakarta.persistence

@Entity
@Getter
@Setter
public class CloudAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String accountName;
    private String provider;
    private String status;

    // AWS-specific fields
    private String awsAccountId;
    private String roleArn;
    private String externalId;

    // GCP-specific fields
    @Lob
    @Column(columnDefinition = "TEXT")
    private String gcpServiceAccountKey;
    private String gcpProjectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;
}