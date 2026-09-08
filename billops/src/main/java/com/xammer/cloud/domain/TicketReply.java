package com.xammer.cloud.domain;

import java.time.LocalDateTime;
import jakarta.persistence.*;

@Entity
public class TicketReply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    /**
     * Null when the reply arrived by mail from an address that belongs to no user — a
     * customer's colleague, a vendor, anyone CC'd onto the thread. {@link #authorEmail} carries
     * who it actually was in that case.
     *
     * <p>Written by the ingestion in xamops-service, which shares this table.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User author;

    /** Sender address, when the reply came from mail and matched no user. */
    @Column(name = "author_email", length = 320)
    private String authorEmail;

    /** Display name off the mail's From header, when it had one. */
    @Column(name = "author_name")
    private String authorName;

    @Lob
    @Column(nullable = false)
    private String message;

    private LocalDateTime createdAt = LocalDateTime.now();

    // --- NEW FIELDS FOR ATTACHMENTS ---
    @Column(name = "attachment_key")
    private String attachmentKey; // S3 Path

    @Column(name = "attachment_name")
    private String attachmentName; // Original filename

    @Column(name = "attachment_type")
    private String attachmentType; // MIME type

    @Lob
    @Column(name = "attachments_json")
    private String attachmentsJson; // JSON array: [{key,name,type}, ...] for multi-file replies
    // ----------------------------------

    @Column(name = "is_internal", nullable = false)
    private boolean isInternal = false; // Admin-only internal note, not visible to customers

    @Column(name = "is_edited", columnDefinition = "boolean not null default false")
    private boolean edited = false;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @Column(name = "is_deleted", columnDefinition = "boolean not null default false")
    private boolean deleted = false;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Ticket getTicket() { return ticket; }
    public void setTicket(Ticket ticket) { this.ticket = ticket; }

    public String getAuthorEmail() { return authorEmail; }
    public void setAuthorEmail(String authorEmail) { this.authorEmail = authorEmail; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    /**
     * What to show as the sender: the account username when there is one, otherwise the name or
     * address off the mail. Never null, so the UI always has something to render.
     */
    public String displayAuthor() {
        if (author != null) {
            return author.getUsername();
        }
        if (authorName != null && !authorName.isBlank()) {
            return authorName;
        }
        return authorEmail != null && !authorEmail.isBlank() ? authorEmail : "Unknown sender";
    }

    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    // --- NEW GETTERS/SETTERS ---
    public String getAttachmentKey() { return attachmentKey; }
    public void setAttachmentKey(String attachmentKey) { this.attachmentKey = attachmentKey; }

    public String getAttachmentName() { return attachmentName; }
    public void setAttachmentName(String attachmentName) { this.attachmentName = attachmentName; }

    public String getAttachmentType() { return attachmentType; }
    public void setAttachmentType(String attachmentType) { this.attachmentType = attachmentType; }

    public String getAttachmentsJson() { return attachmentsJson; }
    public void setAttachmentsJson(String attachmentsJson) { this.attachmentsJson = attachmentsJson; }

    public boolean isInternal() { return isInternal; }
    public void setInternal(boolean isInternal) { this.isInternal = isInternal; }

    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }

    public LocalDateTime getEditedAt() { return editedAt; }
    public void setEditedAt(LocalDateTime editedAt) { this.editedAt = editedAt; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}