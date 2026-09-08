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
}