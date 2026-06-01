package com.xammer.cloud.dto.dbops;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RedisSlowLogDto {
    private String dsnAlias,dbHost,timestamp,command,collectedAt;
    private long id,durationUs;

    public String getDsnAlias(){return dsnAlias;} public void setDsnAlias(String v){this.dsnAlias=v;}
    public String getDbHost(){return dbHost;} public void setDbHost(String v){this.dbHost=v;}
    public long getId(){return id;} public void setId(long v){this.id=v;}
    public String getTimestamp(){return timestamp;} public void setTimestamp(String v){this.timestamp=v;}
    public long getDurationUs(){return durationUs;} public void setDurationUs(long v){this.durationUs=v;}
    public String getCommand(){return command;} public void setCommand(String v){this.command=v;}
    public String getCollectedAt(){return collectedAt;} public void setCollectedAt(String v){this.collectedAt=v;}
}
