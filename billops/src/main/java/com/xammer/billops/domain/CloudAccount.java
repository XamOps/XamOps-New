package com.xammer.billops.domain;

import javax.persistence.*;

@Entity
@Table(name = "cloud_accounts")
public class CloudAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_name")
    private String accountName;

    // ADDED: AWS Account ID field
    @Column(name = "aws_account_id")
    private String awsAccountId;

    // Your other existing fields...

    // Constructors
    public CloudAccount() {}

    public CloudAccount(String accountName, String awsAccountId) {
        this.accountName = accountName;
        this.awsAccountId = awsAccountId;
    }

    // ADDED: Getter and Setter for AWS Account ID
    public String getAwsAccountId() {
        return awsAccountId;
    }

    public void setAwsAccountId(String awsAccountId) {
        this.awsAccountId = awsAccountId;
    }

    // Your other existing getters and setters...
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
}
