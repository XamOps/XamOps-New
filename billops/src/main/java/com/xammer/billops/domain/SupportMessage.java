package com.xammer.billops.domain; // <--- This MUST match the folder structure

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "support_message") // Maps to the same DB table
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
    private boolean isUserMessage; 

    @Column(nullable = false)
    private String status; 

    // In BillOps, we treat the User relation as a simple ID or map it loosely
    // to avoid needing the entire User entity code if it's complex.
    // If you have a User entity in BillOps, you can use @ManyToOne like in XamOps.
    // For safety/simplicity given the error, we often map the ID directly or use the existing User class if available.
    
    // Option A: If you HAVE a User.java in billops/domain:
    /*
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User sender;
    */

    // Option B: If BillOps doesn't strictly need the User object relation here, 
    // we can map the column purely for reading. 
    // HOWEVER, to match your Repository code likely expecting "userId", let's use the ID field.
    @Column(name = "user_id")
    private Long userId; 

    @Column(name = "account_id")
    private String accountId;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "cloud_provider")
    private String cloudProvider;

    @Column(name = "sender_username")
    private String senderUsername;


}