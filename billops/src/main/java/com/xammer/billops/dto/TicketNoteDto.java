package com.xammer.billops.dto;

import java.time.LocalDateTime;

/**
 * Payload for reading/writing a ticket's shared internal note pad.
 * On write (PUT), only non-null fields are applied (partial update).
 */
public class TicketNoteDto {
    private String noteHtml;
    private String tasksJson;
    private String color;
    private String historyJson;
    private LocalDateTime updatedAt;
    private String updatedBy;

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
