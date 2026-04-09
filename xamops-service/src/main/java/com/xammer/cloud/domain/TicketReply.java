package com.xammer.cloud.domain;

import java.time.LocalDateTime;
import javax.persistence.*;

@Entity
public class TicketReply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User author;

    @Lob
    @Column(nullable = false)
    private String message;

    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "is_internal", nullable = false)
    private boolean isInternal = false;

    // --- NEW FIELDS FOR ATTACHMENTS ---
    @Column(name = "attachment_key")
    private String attachmentKey; // S3 Path

    @Column(name = "attachment_name")
    private String attachmentName; // Original filename

    @Column(name = "attachment_type")
    private String attachmentType; // MIME type
    // ----------------------------------

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

    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isInternal() { return isInternal; }
    public void setInternal(boolean isInternal) { this.isInternal = isInternal; }

    // --- NEW GETTERS/SETTERS ---
    public String getAttachmentKey() { return attachmentKey; }
    public void setAttachmentKey(String attachmentKey) { this.attachmentKey = attachmentKey; }

    public String getAttachmentName() { return attachmentName; }
    public void setAttachmentName(String attachmentName) { this.attachmentName = attachmentName; }

    public String getAttachmentType() { return attachmentType; }
    public void setAttachmentType(String attachmentType) { this.attachmentType = attachmentType; }

    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }

    public LocalDateTime getEditedAt() { return editedAt; }
    public void setEditedAt(LocalDateTime editedAt) { this.editedAt = editedAt; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}