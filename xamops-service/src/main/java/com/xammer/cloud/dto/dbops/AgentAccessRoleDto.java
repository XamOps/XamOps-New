package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One role from the agent's db-access-grants doc (pg_roles). Mirrors Go DbAccessRole. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentAccessRoleDto {

    private String name;
    private long oid;
    @JsonProperty("canLogin")           private boolean canLogin;
    @JsonProperty("isSuperuser")        private boolean superuser;
    @JsonProperty("inheritsPrivileges") private boolean inheritsPrivileges;
    @JsonProperty("bypassRls")          private boolean bypassRls;
    @JsonProperty("createRole")         private boolean createRole;
    private int connLimit;
    private String validUntil;

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public long getOid() { return oid; }
    public void setOid(long v) { this.oid = v; }
    public boolean isCanLogin() { return canLogin; }
    public void setCanLogin(boolean v) { this.canLogin = v; }
    public boolean isSuperuser() { return superuser; }
    public void setSuperuser(boolean v) { this.superuser = v; }
    public boolean isInheritsPrivileges() { return inheritsPrivileges; }
    public void setInheritsPrivileges(boolean v) { this.inheritsPrivileges = v; }
    public boolean isBypassRls() { return bypassRls; }
    public void setBypassRls(boolean v) { this.bypassRls = v; }
    public boolean isCreateRole() { return createRole; }
    public void setCreateRole(boolean v) { this.createRole = v; }
    public int getConnLimit() { return connLimit; }
    public void setConnLimit(int v) { this.connLimit = v; }
    public String getValidUntil() { return validUntil; }
    public void setValidUntil(String v) { this.validUntil = v; }
}
