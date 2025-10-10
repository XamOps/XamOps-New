package com.xammer.cloud.dto;


public class AccountDto {
    private Long dbId;
    private String name;
    private String id; // AWS Account ID or GCP Project ID
    private String access;
    private String connection;
    private String status;
    private String roleArn;
    private String externalId;
    private String provider;
    private String grafanaIp;

    public AccountDto() {}

    public AccountDto(Long dbId, String name, String id, String access, String connection, String status, String roleArn, String externalId, String provider) {
        this.dbId = dbId;
        this.name = name;
        this.id = id;
        this.access = access;
        this.connection = connection;
        this.status = status;
        this.roleArn = roleArn;
        this.externalId = externalId;
        this.provider = provider;
    }

    public Long getDbId() { return dbId; }
    public void setDbId(Long dbId) { this.dbId = dbId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAccess() { return access; }
    public void setAccess(String access) { this.access = access; }
    public String getConnection() { return connection; }
    public void setConnection(String connection) { this.connection = connection; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getGrafanaIp() {
        return grafanaIp;
    }

    public void setGrafanaIp(String grafanaIp) {
        this.grafanaIp = grafanaIp;
    }
}