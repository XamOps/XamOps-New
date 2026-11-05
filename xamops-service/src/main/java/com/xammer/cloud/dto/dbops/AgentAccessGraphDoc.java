package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The agent's db-access-grants document for ONE DSN (COL-B1). Stored latest-wins as a single JSON
 * object under cache key {@code db-access-grants:{cloudAccountId}:{dsnAlias}} (saveDocPerDsn), not
 * an array. Mirrors Go DbAccessGraphDoc. {@code AccessGraphService} derives the cytoscape response
 * ({@link AccessGraphDoc}) from this raw document.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentAccessGraphDoc {

    private String dsnAlias;
    private String dbEngine;
    private String dbHost;
    private String collectedAt;
    private List<AgentAccessRoleDto> roles;
    private List<AgentMembershipDto> memberships;
    private List<AgentAccessGrantDto> grants;

    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String v) { this.dsnAlias = v; }
    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String v) { this.dbEngine = v; }
    public String getDbHost() { return dbHost; }
    public void setDbHost(String v) { this.dbHost = v; }
    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String v) { this.collectedAt = v; }
    public List<AgentAccessRoleDto> getRoles() { return roles; }
    public void setRoles(List<AgentAccessRoleDto> v) { this.roles = v; }
    public List<AgentMembershipDto> getMemberships() { return memberships; }
    public void setMemberships(List<AgentMembershipDto> v) { this.memberships = v; }
    public List<AgentAccessGrantDto> getGrants() { return grants; }
    public void setGrants(List<AgentAccessGrantDto> v) { this.grants = v; }
}
