package com.xammer.cloud.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.List;

@Setter
@Getter
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

    private boolean isInternal;

    // legacy single-file fields (kept for backward compat with old rows)
    private String attachmentUrl;
    private String attachmentName;
    private String attachmentType;

    // multi-file attachments
    private List<AttachmentInfo> attachments;

    @Getter
    @Setter
    public static class AttachmentInfo {
        private String url;
        private String name;
        private String type;
    }

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