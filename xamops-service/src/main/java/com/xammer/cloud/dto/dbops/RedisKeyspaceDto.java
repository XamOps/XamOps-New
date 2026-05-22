package com.xammer.cloud.dto.dbops;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RedisKeyspaceDto {
    private String dsnAlias,dbHost,database,collectedAt;
    private long keys,expires,avgTtlMs;

    public String getDsnAlias(){return dsnAlias;} public void setDsnAlias(String v){this.dsnAlias=v;}
    public String getDbHost(){return dbHost;} public void setDbHost(String v){this.dbHost=v;}
    public String getDatabase(){return database;} public void setDatabase(String v){this.database=v;}
    public long getKeys(){return keys;} public void setKeys(long v){this.keys=v;}
    public long getExpires(){return expires;} public void setExpires(long v){this.expires=v;}
    public long getAvgTtlMs(){return avgTtlMs;} public void setAvgTtlMs(long v){this.avgTtlMs=v;}
    public String getCollectedAt(){return collectedAt;} public void setCollectedAt(String v){this.collectedAt=v;}
}
