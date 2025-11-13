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
}