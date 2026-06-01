package com.xammer.cloud.dto.dbops;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MongoIndexStatDto {
    private String dsnAlias,dbHost,database,collection,indexName,accessSince,collectedAt;
    private long accessOps;

    public String getDsnAlias(){return dsnAlias;} public void setDsnAlias(String v){this.dsnAlias=v;}
    public String getDbHost(){return dbHost;} public void setDbHost(String v){this.dbHost=v;}
    public String getDatabase(){return database;} public void setDatabase(String v){this.database=v;}
    public String getCollection(){return collection;} public void setCollection(String v){this.collection=v;}
    public String getIndexName(){return indexName;} public void setIndexName(String v){this.indexName=v;}
    public long getAccessOps(){return accessOps;} public void setAccessOps(long v){this.accessOps=v;}
    public String getAccessSince(){return accessSince;} public void setAccessSince(String v){this.accessSince=v;}
    public String getCollectedAt(){return collectedAt;} public void setCollectedAt(String v){this.collectedAt=v;}
}
