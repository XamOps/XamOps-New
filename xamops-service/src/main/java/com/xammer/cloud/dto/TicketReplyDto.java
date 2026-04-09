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

    // --- EDIT / DELETE FIELDS ---
    private boolean edited;
    private LocalDateTime editedAt;
    private boolean deleted;

    public boolean isInternal() { return isInternal; }
    public void setInternal(boolean isInternal) { this.isInternal = isInternal; }

    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }

    public LocalDateTime getEditedAt() { return editedAt; }
    public void setEditedAt(LocalDateTime editedAt) { this.editedAt = editedAt; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}