package com.xammer.billops.dto;

public class AssignTicketRequest {

    private Long assignedToId;
    private String assignedToUsername;

    public AssignTicketRequest() {}

    public Long getAssignedToId() { return assignedToId; }
    public void setAssignedToId(Long assignedToId) { this.assignedToId = assignedToId; }

    public String getAssignedToUsername() { return assignedToUsername; }
    public void setAssignedToUsername(String assignedToUsername) { this.assignedToUsername = assignedToUsername; }
}
