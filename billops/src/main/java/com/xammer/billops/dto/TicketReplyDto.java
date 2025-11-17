package com.xammer.billops.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Setter
@Getter
public class TicketReplyDto {
    private Long id;
    private Long ticketId;
    private Long authorId;
    private String authorUsername;
    private String message;
    private LocalDateTime createdAt;
    private boolean admin;

    // --- NEW FIELDS ---
    private String attachmentUrl;  // The secure, temporary link
    private String attachmentName;
    private String attachmentType;
}