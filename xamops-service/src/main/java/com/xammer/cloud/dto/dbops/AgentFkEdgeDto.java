package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** One FK relationship (child → parent) from the agent's db-fk-graph doc. Mirrors Go DbFkEdge. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentFkEdgeDto {

    private String childSchema;
    private String childTable;
    private String parentSchema;
    private String parentTable;
    private List<String> columns;
    private String onDelete;
    private String onUpdate;
    private String constraintName;

    public String getChildSchema() { return childSchema; }
    public void setChildSchema(String v) { this.childSchema = v; }
    public String getChildTable() { return childTable; }
    public void setChildTable(String v) { this.childTable = v; }
    public String getParentSchema() { return parentSchema; }
    public void setParentSchema(String v) { this.parentSchema = v; }
    public String getParentTable() { return parentTable; }
    public void setParentTable(String v) { this.parentTable = v; }
    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> v) { this.columns = v; }
    public String getOnDelete() { return onDelete; }
    public void setOnDelete(String v) { this.onDelete = v; }
    public String getOnUpdate() { return onUpdate; }
    public void setOnUpdate(String v) { this.onUpdate = v; }
    public String getConstraintName() { return constraintName; }
    public void setConstraintName(String v) { this.constraintName = v; }
}
