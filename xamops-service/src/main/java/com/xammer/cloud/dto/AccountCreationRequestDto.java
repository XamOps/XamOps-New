package com.xammer.cloud.dto;

public class AccountCreationRequestDto {
    private String accountName;
    private String awsAccountId; // ✅ ADD THIS LINE
    private String accessType;
    private Long clientId;

    /**
     * S3 bucket holding this account's Cost and Usage Report, passed straight through to
     * the CloudFormation stack as CurS3Bucket.
     *
     * <p>Blank for an account under a XamOps payer account, or one with no CUR at all —
     * the template treats blank as "attach no CUR permissions", so those accounts deploy
     * exactly the permission surface they did before CUR support existed.
     */
    private String curS3Bucket;

    /**
     * Which of the four cost-data options the operator picked — OWN_CUR, XAMMER_MPA_CUR,
     * EXISTING_CUR or COST_EXPLORER_ONLY.
     *
     * <p>Decides whether the connection stack creates a Cost and Usage Report. Only
     * OWN_CUR should: a payer-covered account is already in that payer's report, and an
     * account that already has one would be paying to store a second copy.
     */
    private String awsBillingSource;

    // Getters and Setters

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    // ✅ ADD GETTER AND SETTER FOR THE NEW FIELD
    public String getAwsAccountId() {
        return awsAccountId;
    }

    public void setAwsAccountId(String awsAccountId) {
        this.awsAccountId = awsAccountId;
    }

    public String getAccessType() {
        return accessType;
    }

    public void setAccessType(String accessType) {
        this.accessType = accessType;
    }

    public String getCurS3Bucket() {
        return curS3Bucket;
    }

    public void setCurS3Bucket(String curS3Bucket) {
        this.curS3Bucket = curS3Bucket;
    }

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
    }

    public String getAwsBillingSource() {
        return awsBillingSource;
    }

    public void setAwsBillingSource(String awsBillingSource) {
        this.awsBillingSource = awsBillingSource;
    }
}