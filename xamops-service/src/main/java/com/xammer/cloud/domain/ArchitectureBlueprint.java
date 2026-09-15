package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "architecture_blueprints")
public class ArchitectureBlueprint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Stores the raw JSON from Cytoscape
    @Column(name = "graph_data", columnDefinition = "TEXT")
    private String graphData;

    /**
     * Owning tenant.
     *
     * <p>
     * Blueprints previously had no ownership column while the controller served
     * {@code findAll()}, so every tenant saw and could delete every other
     * tenant's architectures. Reads are now scoped on this column; a NULL means
     * an unattributed pre-tenancy row and is never served.
     *
     * <p>
     * {@code @JsonIgnore} on the setter path matters as much as the column: the
     * save endpoint used to bind a whole {@link ArchitectureBlueprint} straight
     * from the request body, so a client could otherwise nominate its own
     * {@code clientId} and write into another tenant.
     */
    @Column(name = "client_id")
    @JsonIgnore
    private Long clientId;

    /** app_user.id of the author. Attribution only — not an access boundary. */
    @Column(name = "created_by")
    @JsonIgnore
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}