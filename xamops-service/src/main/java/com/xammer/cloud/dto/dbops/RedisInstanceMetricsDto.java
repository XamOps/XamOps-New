package com.xammer.cloud.dto.dbops;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RedisInstanceMetricsDto {
    private String dsnAlias,dbHost,role,version,collectedAt;
    private long usedMemoryBytes,maxMemoryBytes,keyspaceHits,keyspaceMisses;
    private long evictedKeys,expiredKeys,totalCommandsProcessed,connectedClients,uptimeSeconds;
    private double memUsagePct,hitRatioPct,replLagSeconds;

    public String getDsnAlias(){return dsnAlias;} public void setDsnAlias(String v){this.dsnAlias=v;}
    public String getDbHost(){return dbHost;} public void setDbHost(String v){this.dbHost=v;}
    public long getUsedMemoryBytes(){return usedMemoryBytes;} public void setUsedMemoryBytes(long v){this.usedMemoryBytes=v;}
    public long getMaxMemoryBytes(){return maxMemoryBytes;} public void setMaxMemoryBytes(long v){this.maxMemoryBytes=v;}
    public double getMemUsagePct(){return memUsagePct;} public void setMemUsagePct(double v){this.memUsagePct=v;}
    public long getKeyspaceHits(){return keyspaceHits;} public void setKeyspaceHits(long v){this.keyspaceHits=v;}
    public long getKeyspaceMisses(){return keyspaceMisses;} public void setKeyspaceMisses(long v){this.keyspaceMisses=v;}
    public double getHitRatioPct(){return hitRatioPct;} public void setHitRatioPct(double v){this.hitRatioPct=v;}
    public long getEvictedKeys(){return evictedKeys;} public void setEvictedKeys(long v){this.evictedKeys=v;}
    public long getExpiredKeys(){return expiredKeys;} public void setExpiredKeys(long v){this.expiredKeys=v;}
    public long getTotalCommandsProcessed(){return totalCommandsProcessed;} public void setTotalCommandsProcessed(long v){this.totalCommandsProcessed=v;}
    public long getConnectedClients(){return connectedClients;} public void setConnectedClients(long v){this.connectedClients=v;}
    public String getRole(){return role;} public void setRole(String v){this.role=v;}
    public double getReplLagSeconds(){return replLagSeconds;} public void setReplLagSeconds(double v){this.replLagSeconds=v;}
    public long getUptimeSeconds(){return uptimeSeconds;} public void setUptimeSeconds(long v){this.uptimeSeconds=v;}
    public String getVersion(){return version;} public void setVersion(String v){this.version=v;}
    public String getCollectedAt(){return collectedAt;} public void setCollectedAt(String v){this.collectedAt=v;}
}
