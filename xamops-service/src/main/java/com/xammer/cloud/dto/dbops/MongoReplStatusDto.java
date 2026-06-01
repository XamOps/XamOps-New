package com.xammer.cloud.dto.dbops;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MongoReplStatusDto {
    private String dsnAlias,dbHost,setName,collectedAt;
    private int myState;
    private double lagSeconds;

    public String getDsnAlias(){return dsnAlias;} public void setDsnAlias(String v){this.dsnAlias=v;}
    public String getDbHost(){return dbHost;} public void setDbHost(String v){this.dbHost=v;}
    public String getSetName(){return setName;} public void setSetName(String v){this.setName=v;}
    public int getMyState(){return myState;} public void setMyState(int v){this.myState=v;}
    public double getLagSeconds(){return lagSeconds;} public void setLagSeconds(double v){this.lagSeconds=v;}
    public String getCollectedAt(){return collectedAt;} public void setCollectedAt(String v){this.collectedAt=v;}
}
