package com.xammer.cloud.domain;

import java.time.LocalDateTime;
import jakarta.persistence.*;

/**
 * A single shared internal note pad per ticket (admin-only).
 *
 * Replaces the previous browser-localStorage-only notes so that internal notes,
 * task checklists, colour and short edit-history are persisted server-side and
 * visible to every agent that opens the ticket — not just the device that wrote
 * them.
 */
@Entity
@Table(name = "ticket_note")
public class TicketNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** One note per ticket. */
    @Column(name = "ticket_id", nullable = false, unique = true)
    private Long ticketId;

    @Lob
    @Column(name = "note_html")
    private String noteHtml;

    @Lob
    @Column(name = "tasks_json")
    private String tasksJson; // JSON array: [{id,text,done}, ...]

    @Column(name = "color")
    private String color;

    @Lob
    @Column(name = "history_json")
    private String historyJson; // JSON array: [{html,savedAt}, ...]

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

    public String getNoteHtml() { return noteHtml; }
    public void setNoteHtml(String noteHtml) { this.noteHtml = noteHtml; }

    public String getTasksJson() { return tasksJson; }
    public void setTasksJson(String tasksJson) { this.tasksJson = tasksJson; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getHistoryJson() { return historyJson; }
    public void setHistoryJson(String historyJson) { this.historyJson = historyJson; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
