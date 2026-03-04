package com.xammer.cloud.dto;

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

    private boolean isInternal;

    // --- NEW FIELDS ---
    private String attachmentUrl;  // The secure, temporary link
    private String attachmentName;
    private String attachmentType;

    public boolean isInternal() { return isInternal; }
    public void setInternal(boolean isInternal) { this.isInternal = isInternal; }
}