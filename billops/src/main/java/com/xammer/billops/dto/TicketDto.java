package com.xammer.billops.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

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

}