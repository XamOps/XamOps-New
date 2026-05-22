package com.xammer.cloud.dto.dbops;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MongoInstanceMetricsDto {
    private String dsnAlias; private String dbHost;
    private long currentConnections; private long availableConnections; private long totalCreatedConnections;
    private long residentMemMb; private long virtualMemMb;
    private long opsInsert; private long opsQuery; private long opsUpdate;
    private long opsDelete; private long opsGetmore; private long opsCommand;
    private long networkBytesIn; private long networkBytesOut;
    private long uptimeSeconds; private String version; private String collectedAt;

    public String getDsnAlias(){return dsnAlias;} public void setDsnAlias(String v){this.dsnAlias=v;}
    public String getDbHost(){return dbHost;} public void setDbHost(String v){this.dbHost=v;}
    public long getCurrentConnections(){return currentConnections;} public void setCurrentConnections(long v){this.currentConnections=v;}
    public long getAvailableConnections(){return availableConnections;} public void setAvailableConnections(long v){this.availableConnections=v;}
    public long getTotalCreatedConnections(){return totalCreatedConnections;} public void setTotalCreatedConnections(long v){this.totalCreatedConnections=v;}
    public long getResidentMemMb(){return residentMemMb;} public void setResidentMemMb(long v){this.residentMemMb=v;}
    public long getVirtualMemMb(){return virtualMemMb;} public void setVirtualMemMb(long v){this.virtualMemMb=v;}
    public long getOpsInsert(){return opsInsert;} public void setOpsInsert(long v){this.opsInsert=v;}
    public long getOpsQuery(){return opsQuery;} public void setOpsQuery(long v){this.opsQuery=v;}
    public long getOpsUpdate(){return opsUpdate;} public void setOpsUpdate(long v){this.opsUpdate=v;}
    public long getOpsDelete(){return opsDelete;} public void setOpsDelete(long v){this.opsDelete=v;}
    public long getOpsGetmore(){return opsGetmore;} public void setOpsGetmore(long v){this.opsGetmore=v;}
    public long getOpsCommand(){return opsCommand;} public void setOpsCommand(long v){this.opsCommand=v;}
    public long getNetworkBytesIn(){return networkBytesIn;} public void setNetworkBytesIn(long v){this.networkBytesIn=v;}
    public long getNetworkBytesOut(){return networkBytesOut;} public void setNetworkBytesOut(long v){this.networkBytesOut=v;}
    public long getUptimeSeconds(){return uptimeSeconds;} public void setUptimeSeconds(long v){this.uptimeSeconds=v;}
    public String getVersion(){return version;} public void setVersion(String v){this.version=v;}
    public String getCollectedAt(){return collectedAt;} public void setCollectedAt(String v){this.collectedAt=v;}
}
