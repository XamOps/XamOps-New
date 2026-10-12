package com.xammer.billops.dto;

/**
 * One chat-content search hit: the ticket the message belongs to, plus a short snippet of the
 * matching text so the admin UI can render a distinct "Chats" result section (keyword in the
 * conversation) separately from the "Tickets" results (keyword in id/subject/account/etc.).
 *
 * <p>{@code source} is {@code "description"} for the ticket's opening message (the first chat
 * bubble) or {@code "reply"} for a subsequent reply.
 */
public class ChatSearchMatchDto {

    private Long ticketId;
    private String snippet;
    private String source;
    /** ISO-8601 timestamp of the matched message (reply time, or ticket creation for the opening message). */
    private String createdAt;

    public ChatSearchMatchDto() {
    }

    public ChatSearchMatchDto(Long ticketId, String snippet, String source, String createdAt) {
        this.ticketId = ticketId;
        this.snippet = snippet;
        this.source = source;
        this.createdAt = createdAt;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
