package com.xammer.cloud.domain;

// CHANGE THIS LINE: Use javax instead of jakarta for Spring Boot 2.x
import javax.persistence.*; 
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