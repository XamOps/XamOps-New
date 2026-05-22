package com.xammer.cloud.dto.dbops;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MongoSlowOpDto {
    private String dsnAlias,dbHost,namespace,op,queryDigest,timestamp,collectedAt;
    private long millis,nReturned,nScanned;

    public String getDsnAlias(){return dsnAlias;} public void setDsnAlias(String v){this.dsnAlias=v;}
    public String getDbHost(){return dbHost;} public void setDbHost(String v){this.dbHost=v;}
    public String getNamespace(){return namespace;} public void setNamespace(String v){this.namespace=v;}
    public String getOp(){return op;} public void setOp(String v){this.op=v;}
    public String getQueryDigest(){return queryDigest;} public void setQueryDigest(String v){this.queryDigest=v;}
    public long getMillis(){return millis;} public void setMillis(long v){this.millis=v;}
    public String getTimestamp(){return timestamp;} public void setTimestamp(String v){this.timestamp=v;}
    public long getNReturned(){return nReturned;} public void setNReturned(long v){this.nReturned=v;}
    public long getNScanned(){return nScanned;} public void setNScanned(long v){this.nScanned=v;}
    public String getCollectedAt(){return collectedAt;} public void setCollectedAt(String v){this.collectedAt=v;}
}
