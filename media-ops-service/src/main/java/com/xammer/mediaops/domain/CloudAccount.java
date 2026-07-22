package com.xammer.mediaops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * Read-only view of the shared {@code cloud_account} table (owned by xamops-service).
 * media-ops only needs the few fields required to assume a customer's onboarding
 * IAM role and write transcoded output to one of that account's S3 buckets — the
 * same cross-account role the rest of XamOps uses to read the account. This entity
 * is never written by media-ops.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "cloud_account")
public class CloudAccount {

    @Id
    private Long id;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "aws_account_id")
    private String awsAccountId;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "role_arn")
    private String roleArn;

    @Column(name = "access_type")
    private String accessType;

    @Column(name = "provider")
    private String provider;

    @Column(name = "status")
    private String status;

    // Foreign key to client — mapped as a plain column so media-ops needn't pull in
    // the full Client entity graph. Used only to scope lookups to the caller's client.
    @Column(name = "client_id")
    private Long clientId;
}
