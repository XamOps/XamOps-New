package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*;
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
    @Lob
    @Column(columnDefinition = "TEXT")
    private String scheduleJson;

    private boolean notificationsEnabled;
    private String notificationEmail;

    @ManyToOne
    @JoinColumn(name = "client_id")
    private Client client;
}