package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "support_message")
@Data
@NoArgsConstructor
public class SupportMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // TEXT, not the JPA-default varchar(255): chatbot FAQ auto-answers are full
    // sentences plus a follow-up nudge and routinely exceed 255 characters.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false)
    private boolean isUserMessage; // true = XamOps User, false = BillOps Admin

    @Column(nullable = false)
    private String status; // SENT, DELIVERED, READ

    // --- Context Data for BillOps Admin ---

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true)
    @JsonIgnore
    private User sender;

    @Column(name = "account_id")
    private String accountId; // e.g., AWS Account ID or GCP Project ID

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "cloud_provider")
    private String cloudProvider; // AWS, GCP, Azure

    @Column(name = "sender_username")
    private String senderUsername;

    // Edit / delete (WhatsApp-style)
    @JsonProperty("isEdited")
    @Column(name = "is_edited", nullable = false)
    private boolean isEdited = false;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @JsonProperty("isDeleted")
    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    public SupportMessage(User sender, String content, boolean isUserMessage) {
        this.sender = sender;
        this.content = content;
        this.isUserMessage = isUserMessage;
        this.timestamp = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        this.status = isUserMessage ? "UNREAD" : "SENT";
    }
}