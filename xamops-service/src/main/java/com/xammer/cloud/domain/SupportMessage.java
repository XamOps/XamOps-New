package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*;
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

    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false)
    private boolean isUserMessage; // true = XamOps User, false = BillOps Admin

    @Column(nullable = false)
    private String status; // SENT, DELIVERED, READ

    // --- Context Data for BillOps Admin ---
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User sender;

    @Column(name = "account_id")
    private String accountId; // e.g., AWS Account ID or GCP Project ID

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "cloud_provider")
    private String cloudProvider; // AWS, GCP, Azure

    public SupportMessage(User sender, String content, boolean isUserMessage) {
        this.sender = sender;
        this.content = content;
        this.isUserMessage = isUserMessage;
        this.timestamp = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        this.status = "SENT";
    }
}