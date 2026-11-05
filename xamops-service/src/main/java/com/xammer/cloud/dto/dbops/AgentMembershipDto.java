package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One role membership edge from the agent's db-access-grants doc (pg_auth_members). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentMembershipDto {

    private String member;
    private String grantedRole;
    private boolean withAdminOption;

    public String getMember() { return member; }
    public void setMember(String v) { this.member = v; }
    public String getGrantedRole() { return grantedRole; }
    public void setGrantedRole(String v) { this.grantedRole = v; }
    public boolean isWithAdminOption() { return withAdminOption; }
    public void setWithAdminOption(boolean v) { this.withAdminOption = v; }
}
