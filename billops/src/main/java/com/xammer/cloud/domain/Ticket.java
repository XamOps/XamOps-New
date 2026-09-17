package com.xammer.cloud.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import com.xammer.billops.domain.Client;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    // --- START OF MODIFICATION ---
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_user_id") // This will be a new column in your 'ticket' table
    private User creator;
    // --- END OF MODIFICATION ---

    @Column(nullable = false)
    private String subject;

    @Lob
    private String description;

    private String status; // e.g., "OPEN", "IN_PROGRESS", "CLOSED"

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    // NEW FIELDS TO MATCH THE FORM
    private String category;
    private String service;
    private String severity;
    private String accountId;
    private String region;

    private LocalDateTime closedAt;

    @Column(name = "last_admin_reply_at")
    private LocalDateTime lastAdminReplyAt;

    @Column(name = "reminder_sent_at")
    private LocalDateTime reminderSentAt;

    /**
     * Stop-the-clock resolution SLA. {@code slaActiveMs} is the banked "awaiting-admin" time —
     * the total time the ball has been in support's court (creation→first admin reply, and each
     * customer reply→next admin reply). The clock is paused while waiting on the customer.
     * {@code slaRunningSince} is non-null only while the clock is currently running; effective
     * elapsed = slaActiveMs + (slaRunningSince != null ? now - slaRunningSince : 0). Breach is
     * elapsed > the severity target. Internal admin notes do not pause the clock.
     *
     * <p>slaActiveMs carries its own DEFAULT for the same reason as {@code source}: ddl-auto=update
     * emits a bare NOT NULL ADD COLUMN that Postgres rejects on the already-populated ticket table.
     */
    @Column(name = "sla_active_ms", columnDefinition = "bigint not null default 0")
    private long slaActiveMs = 0L;

    /** Timestamp the SLA clock started running; null while paused (awaiting customer) or closed. */
    @Column(name = "sla_running_since")
    private LocalDateTime slaRunningSince;

    private LocalDateTime chatStartedAt;

    private Long assignedToId;

    private String assignedToUsername;

    /**
     * How the ticket was raised: {@code WEB} through the app, {@code EMAIL} from a mail that
     * mentioned one of the support group addresses. Written by the ingestion in xamops-service,
     * which shares this table.
     *
     * <p>The column definition carries its own DEFAULT because environments that manage schema
     * with {@code ddl-auto=update} rather than Flyway generate a bare
     * {@code ADD COLUMN ... NOT NULL}, which Postgres rejects on a table that already has rows.
     * Without the default the column is never created and every ticket query fails.
     */
    @Column(name = "source", columnDefinition = "varchar(16) not null default 'WEB'")
    private String source = SOURCE_WEB;

    public static final String SOURCE_WEB = "WEB";
    public static final String SOURCE_EMAIL = "EMAIL";

    /** True when the latest non-internal reply is from a user (not admin) — drives READ/UNREAD indicator */
    @Column(name = "has_new_user_reply", nullable = false)
    private boolean hasNewUserReply = false;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<TicketReply> replies = new ArrayList<>();


    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    // --- START: ADDED GETTER/SETTER FOR CREATOR ---
    public User getCreator() {
        return creator;
    }

    public void setCreator(User creator) {
        this.creator = creator;
    }
    // --- END: ADDED GETTER/SETTER FOR CREATOR ---

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public List<TicketReply> getReplies() {
        return replies;
    }

    public void setReplies(List<TicketReply> replies) {
        this.replies = replies;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public LocalDateTime getChatStartedAt() {
        return chatStartedAt;
    }

    public void setChatStartedAt(LocalDateTime chatStartedAt) {
        this.chatStartedAt = chatStartedAt;
    }

    public Long getAssignedToId() {
        return assignedToId;
    }

    public void setAssignedToId(Long assignedToId) {
        this.assignedToId = assignedToId;
    }

    public String getAssignedToUsername() {
        return assignedToUsername;
    }

    public void setAssignedToUsername(String assignedToUsername) {
        this.assignedToUsername = assignedToUsername;
    }

    public boolean isHasNewUserReply() { return hasNewUserReply; }
    public void setHasNewUserReply(boolean hasNewUserReply) { this.hasNewUserReply = hasNewUserReply; }

    public LocalDateTime getLastAdminReplyAt() { return lastAdminReplyAt; }
    public void setLastAdminReplyAt(LocalDateTime lastAdminReplyAt) { this.lastAdminReplyAt = lastAdminReplyAt; }

    public LocalDateTime getReminderSentAt() { return reminderSentAt; }
    public void setReminderSentAt(LocalDateTime reminderSentAt) { this.reminderSentAt = reminderSentAt; }

    public long getSlaActiveMs() { return slaActiveMs; }
    public void setSlaActiveMs(long slaActiveMs) { this.slaActiveMs = slaActiveMs; }

    public LocalDateTime getSlaRunningSince() { return slaRunningSince; }
    public void setSlaRunningSince(LocalDateTime slaRunningSince) { this.slaRunningSince = slaRunningSince; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}