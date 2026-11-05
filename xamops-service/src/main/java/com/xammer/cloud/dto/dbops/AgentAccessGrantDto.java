package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One aggregated grant bucket from the agent's db-access-grants doc: a (grantee, scope,
 * privilegeClass) tuple with an object count and a capped object-name sample. Mirrors Go DbAccessGrant.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentAccessGrantDto {

    private String grantee;         // role name; "PUBLIC" for grantee OID 0
    private String scope;           // schema for object/schema grants, db name for CONNECT
    private String privilegeClass;  // READ | WRITE | DDL | USAGE_EXEC | CONNECT | ADMIN
    private int objectCount;
    private boolean hasPublic;
    private List<String> sampleObjects;

    public String getGrantee() { return grantee; }
    public void setGrantee(String v) { this.grantee = v; }
    public String getScope() { return scope; }
    public void setScope(String v) { this.scope = v; }
    public String getPrivilegeClass() { return privilegeClass; }
    public void setPrivilegeClass(String v) { this.privilegeClass = v; }
    public int getObjectCount() { return objectCount; }
    public void setObjectCount(int v) { this.objectCount = v; }
    public boolean isHasPublic() { return hasPublic; }
    public void setHasPublic(boolean v) { this.hasPublic = v; }
    public List<String> getSampleObjects() { return sampleObjects; }
    public void setSampleObjects(List<String> v) { this.sampleObjects = v; }
}
