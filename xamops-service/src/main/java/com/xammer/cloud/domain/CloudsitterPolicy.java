package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@Table(name = "cloudsitter_policies")
public class CloudsitterPolicy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name; // e.g., "Weekdays 9-5"
    private String description;
    private String type; // "SCHEDULE" (or "IDLE" for future use)
    private String timeZone; // e.g., "Asia/Kolkata"

    // Store the schedule as a JSON string (e.g., 24x7 boolean grid or cron list)
    // No @Lob: the column is TEXT, and on PostgreSQL @Lob makes Hibernate treat it as a
    // large object -- read through a LOB stream, which fails outside a transaction with
    // "Unable to access lob stream" / "Large Objects may not be used in auto-commit mode".
    // Hibernate 5 tolerated the mismatch; Hibernate 7 does not. columnDefinition = "TEXT"
    // already gives unbounded storage. Same fix as ScriptVersion/DevOpsScript/JenkinsIntegrationConfig.
    @Column(columnDefinition = "TEXT")
    private String scheduleJson;

    private boolean notificationsEnabled;
    private String notificationEmail;

    @ManyToOne
    @JoinColumn(name = "client_id")
    private Client client;
}