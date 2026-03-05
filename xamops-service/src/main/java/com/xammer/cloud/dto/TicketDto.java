package com.xammer.cloud.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List; 

@Setter
@Getter
public class TicketDto {

    // Getters and Setters
    private Long id;
    private String subject;
    private String description;
    private String status;
    private LocalDateTime createdAt;
    private Long clientId;

    // NEW FIELDS
    private String category;
    private String service;
    private String severity;
    private String accountId;
    private String region;

    private LocalDateTime closedAt;
    private LocalDateTime chatStartedAt;
    private Long assignedToId;
    private String assignedToUsername;
    private String creatorUsername;

    // --- START: MODIFIED SECTION ---
    private List<TicketReplyDto> replies;

    private Long creatorId; // Added to pass the creator's ID
    // --- END: MODIFIED SECTION ---

    // --- Manually added getter/setter for creatorId ---
    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long creatorId) {
        this.creatorId = creatorId;
    }

    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }

    public LocalDateTime getChatStartedAt() { return chatStartedAt; }
    public void setChatStartedAt(LocalDateTime chatStartedAt) { this.chatStartedAt = chatStartedAt; }

    public Long getAssignedToId() { return assignedToId; }
    public void setAssignedToId(Long assignedToId) { this.assignedToId = assignedToId; }

    public String getAssignedToUsername() { return assignedToUsername; }
    public void setAssignedToUsername(String assignedToUsername) { this.assignedToUsername = assignedToUsername; }

    public String getCreatorUsername() { return creatorUsername; }
    public void setCreatorUsername(String creatorUsername) { this.creatorUsername = creatorUsername; }
}