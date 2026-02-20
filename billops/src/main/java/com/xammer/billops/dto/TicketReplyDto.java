package com.xammer.billops.dto;

import java.time.LocalDateTime;

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

    public TicketReplyDto() {
    }

    public TicketReplyDto(Long id, Long ticketId, Long authorId, String authorUsername, String message, LocalDateTime createdAt, boolean admin, String attachmentUrl, String attachmentName, String attachmentType) {
        this.id = id;
        this.ticketId = ticketId;
        this.authorId = authorId;
        this.authorUsername = authorUsername;
        this.message = message;
        this.createdAt = createdAt;
        this.admin = admin;
        this.attachmentUrl = attachmentUrl;
        this.attachmentName = attachmentName;
        this.attachmentType = attachmentType;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getAuthorUsername() {
        return authorUsername;
    }

    public void setAuthorUsername(String authorUsername) {
        this.authorUsername = authorUsername;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public String getAttachmentUrl() {
        return attachmentUrl;
    }

    public void setAttachmentUrl(String attachmentUrl) {
        this.attachmentUrl = attachmentUrl;
    }

    public String getAttachmentName() {
        return attachmentName;
    }

    public void setAttachmentName(String attachmentName) {
        this.attachmentName = attachmentName;
    }

    public String getAttachmentType() {
        return attachmentType;
    }

    public void setAttachmentType(String attachmentType) {
        this.attachmentType = attachmentType;
    }
}