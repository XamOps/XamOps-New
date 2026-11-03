package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One FK-participating table from the agent's db-fk-graph doc. {@code sizeBytes}/{@code rowEstimate}
 * are catalog-derived by COL-C1 itself; {@code activityScore} is enriched server-side from the
 * db-table-activity feed (null when the table is absent from it). Mirrors Go DbFkTable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentFkTableDto {

    private String schema;
    private String name;
    private long oid;
    private long sizeBytes;
    private long rowEstimate;
    private Double activityScore; // nullable — only for tables in the top-100 db-table-activity feed

    public String getSchema() { return schema; }
    public void setSchema(String v) { this.schema = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public long getOid() { return oid; }
    public void setOid(long v) { this.oid = v; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { this.sizeBytes = v; }
    public long getRowEstimate() { return rowEstimate; }
    public void setRowEstimate(long v) { this.rowEstimate = v; }
    public Double getActivityScore() { return activityScore; }
    public void setActivityScore(Double v) { this.activityScore = v; }
}
