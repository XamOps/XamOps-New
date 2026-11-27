package com.xammer.cloud.dto.dbops;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbInstanceDto {
    private String instanceId;
    private String instanceClass;
    private String engine;
    private String engineVersion;
    private String status;
    private String region;
    private String multiAz;
    private String storageType;
    private int allocatedStorage;
    private boolean publiclyAccessible;
    private boolean storageEncrypted;
    private boolean backupEnabled;
    private String backupRetentionDays;
    private String endpoint;
    private double estimatedMonthlyCost;
    private int maxConnections;
    /**
     * Minimum TLS version the server accepts, as the cloud reports it (Azure only: e.g. "1.2",
     * "TLSv1.2"). Null when not read. Azure used to put a hardcoded "TLS1_2" into storageType.
     */
    private String minTlsVersion;
}
