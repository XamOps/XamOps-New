package com.xammer.billops.dto;

import java.time.LocalDateTime;

public class TicketReplyDto {
    private Long id;
    private Long ticketId;
    private Long authorId;
    private String authorUsername;

    /**
     * Sender address, when the reply arrived by mail from somebody with no account. In that case
     * {@link #authorId} is null and {@link #authorUsername} carries their display name or
     * address, so the UI always has a sender to render.
     */
    private String authorEmail;

    /** True when this reply came from an email rather than the app. */
    private boolean fromEmail;

    private String message;
    private LocalDateTime createdAt;
    private boolean admin;

    // --- NEW FIELDS ---
    private String attachmentUrl;  // The secure, temporary link
    private String attachmentName;
    private String attachmentType;
    private boolean isInternal; // Admin-only internal note
    private boolean edited;
    private LocalDateTime editedAt;
    private boolean deleted;

    // multi-file attachments (preferred over the single legacy fields above)
    private java.util.List<AttachmentInfo> attachments;

    public static class AttachmentInfo {
        private String url;
        private String name;
        private String type;

        public AttachmentInfo() {}
        public AttachmentInfo(String url, String name, String type) {
            this.url = url; this.name = name; this.type = type;
        }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

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

    public String getAuthorEmail() {
        return authorEmail;
    }

    public void setAuthorEmail(String authorEmail) {
        this.authorEmail = authorEmail;
    }

    public boolean isFromEmail() {
        return fromEmail;
    }

    public void setFromEmail(boolean fromEmail) {
        this.fromEmail = fromEmail;
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

    public boolean isInternal() {
        return isInternal;
    }

    public void setInternal(boolean isInternal) {
        this.isInternal = isInternal;
    }

    public boolean isEdited() {
        return edited;
    }

    public void setEdited(boolean edited) {
        this.edited = edited;
    }

    public LocalDateTime getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(LocalDateTime editedAt) {
        this.editedAt = editedAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public java.util.List<AttachmentInfo> getAttachments() {
        return attachments;
    }

    public void setAttachments(java.util.List<AttachmentInfo> attachments) {
        this.attachments = attachments;
    }
}