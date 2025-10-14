package com.xammer.cloud.dto;


import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List; // ADD THIS IMPORT

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
    private List<TicketReplyDto> replies; // Add this line to hold replies
    // --- END: MODIFIED SECTION ---
} 
    

