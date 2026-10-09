package com.xammer.cloud.domain;

import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.domain.type.OidShapeGuard;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@DynamicUpdate
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
    @Type(value = LegacyLobTextType.class, parameters = @Parameter(name = LegacyLobTextType.KEY_COLUMN, value = "id"))
    @Column(columnDefinition = "TEXT")
    private String scheduleJson;

    private boolean notificationsEnabled;
    private String notificationEmail;

    @ManyToOne
    @JoinColumn(name = "client_id")
    private Client client;

    // Refuses an OID-shaped inline value in both write modes (OidShapeGuard).
    @PrePersist
    @PreUpdate
    private void rejectOidShapedScheduleJson() {
        OidShapeGuard.checkEntityWrite("cloudsitter_policies", id, scheduleJson);
    }
}